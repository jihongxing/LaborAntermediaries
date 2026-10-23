package com.company.labor.foundation.storage;

import com.company.labor.common.exception.BusinessException;
import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.audit.AuditRecorder;
import com.company.labor.foundation.context.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

@Service
public class PrivateFileStorage {
    private final JdbcTemplate jdbc;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;
    private final List<FileAccessPolicy> policies;
    private final Path root;
    private final long maxBytes;

    public record Metadata(String id, long tenantId, long ownerId, String originalName, long sizeBytes) {
    }

    public record Download(Metadata metadata, InputStream stream) {
    }

    public PrivateFileStorage(JdbcTemplate jdbc, FoundationProperties properties, List<FileAccessPolicy> policies,
                              AuditRecorder audit, PlatformTransactionManager manager) throws IOException {
        this.jdbc = jdbc;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
        this.policies = List.copyOf(policies);
        this.maxBytes = properties.getStorage().getMaxBytes();
        Path configured = properties.getStorage().getRoot().toAbsolutePath().normalize();
        Files.createDirectories(configured);
        this.root = configured.toRealPath();
    }

    public Metadata store(InputStream input, String originalName) {
        long tenant = ExecutionContext.requireTenant();
        long actor = ExecutionContext.require().actorId();
        String safeName = safeName(originalName);
        String id = UUID.randomUUID().toString().replace("-", "");
        Path target = root.resolve(id);
        return transaction.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completion) {
                    if (completion != STATUS_COMMITTED) {
                        delete(target);
                    }
                }
            });
            Path temporary = null;
            try {
                temporary = Files.createTempFile(root, "incoming-", ".tmp");
                long size = copyLimited(input, temporary);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                Metadata metadata = new Metadata(id, tenant, actor, safeName, size);
                jdbc.update("""
                        INSERT INTO foundation_file(id, tenant_id, owner_id, original_name, size_bytes) VALUES (?, ?, ?, ?, ?)
                        """, id, tenant, actor, safeName, size);
                audit.record("file.upload", "file", id, "SUCCESS");
                return metadata;
            } catch (IOException exception) {
                throw new IllegalStateException("Private file storage failed", exception);
            } finally {
                if (temporary != null) {
                    delete(temporary);
                }
            }
        });
    }

    public Download open(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{32}")) {
            throw new BusinessException(404, "File not found");
        }
        long tenant = ExecutionContext.requireTenant();
        List<Metadata> rows = jdbc.query("""
                SELECT id, tenant_id, owner_id, original_name, size_bytes FROM foundation_file WHERE id = ? AND tenant_id = ?
                """, (row, index) -> new Metadata(row.getString("id"), row.getLong("tenant_id"), row.getLong("owner_id"),
                row.getString("original_name"), row.getLong("size_bytes")), id, tenant);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "File not found");
        }
        Metadata metadata = rows.get(0);
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (metadata.ownerId() != identity.actorId() && policies.stream().noneMatch(policy -> policy.mayRead(identity, metadata))) {
            audit.record("file.download", "file", id, "DENIED");
            throw new BusinessException(403, "File access denied");
        }
        Path path = root.resolve(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != metadata.sizeBytes()) {
            throw new IllegalStateException("Stored file is missing or inconsistent");
        }
        InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            audit.record("file.download", "file", id, "SUCCESS");
            return new Download(metadata, input);
        } catch (RuntimeException exception) {
            input.close();
            throw exception;
        }
    }

    private long copyLimited(InputStream input, Path destination) throws IOException {
        long count = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > maxBytes) {
                    throw new BusinessException(413, "File exceeds configured size limit");
                }
                output.write(buffer, 0, read);
            }
        }
        return count;
    }

    private String safeName(String name) {
        if (name == null || name.isBlank() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid file name");
        }
        String normalized = name.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (basename.isBlank() || basename.equals(".") || basename.equals("..")) {
            throw new IllegalArgumentException("Invalid file name");
        }
        return basename;
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Private file cleanup failed", exception);
        }
    }
}
