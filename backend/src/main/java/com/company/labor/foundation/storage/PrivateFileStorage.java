package com.company.labor.foundation.storage;

import com.company.labor.common.error.PlatformError;

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

/**
 * 将文件字节保存在私有目录，元数据按组织隔离；同组织内再校验所有者或扩展读取策略。
 * 文件落盘与数据库不能组成真正的分布式原子事务，回滚清理属于进程内补偿。
 */
@Service
public class PrivateFileStorage {
    /** 文件元数据的数据库访问入口。 */
    private final JdbcTemplate jdbc;
    /** 上传和下载事件的审计记录器。 */
    private final AuditRecorder audit;
    /** 参与当前数据库事务的模板，用于协调元数据与回滚补偿。 */
    private final TransactionTemplate transaction;
    /** 同组织非所有者的附加读取授权策略，不允许覆盖组织边界。 */
    private final List<FileAccessPolicy> policies;
    /** 初始化时解析后的真实绝对根目录，存储名称只使用服务端生成的标识。 */
    private final Path root;
    /** 单文件允许的最大字节数，读取过程中执行限制。 */
    private final long maxBytes;

    /**
     * 可对外返回的文件元数据，不包含磁盘路径。
     * @param id 服务端生成的 32 位小写十六进制标识
     * @param tenantId 文件所属组织
     * @param ownerId 上传操作者标识
     * @param originalName 去除客户端路径部分后的附件名称
     * @param sizeBytes 实际落盘字节数
     */
    public record Metadata(String id, long tenantId, long ownerId, String originalName, long sizeBytes) {
    }

    /**
     * 已授权下载的元数据和打开的内容流。
     * @param metadata 文件元数据
     * @param stream 调用方或 HTTP 响应处理器必须消费并关闭的输入流
     */
    public record Download(Metadata metadata, InputStream stream) {
    }

    /**
     * 初始化私有存储目录并复制授权策略。
     * @param jdbc 元数据访问模板
     * @param properties 存储根目录与大小限制配置
     * @param policies 同组织非所有者的读取策略
     * @param audit 审计记录器
     * @param manager 数据库事务管理器
     * @throws IOException 根目录创建或真实路径解析失败
     */
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

    /**
     * 流式限量写入临时文件，再原子移动并保存组织元数据与审计。
     * 不关闭调用方输入流；数据库事务回滚时尝试删除目标文件，不保证进程崩溃后的自动清理。
     * @param input 调用方负责关闭的文件内容流
     * @param originalName 客户端原始名称，仅用作附件名称，不用于生成存储路径
     * @return 文件元数据
     * @throws BusinessException 未认证、缺少组织范围或文件超限
     * @throws IllegalArgumentException 文件名不合法
     * @throws IllegalStateException 文件存储、移动或补偿清理失败
     */
    public Metadata store(InputStream input, String originalName) {
        long tenant = ExecutionContext.requireTenant();
        long actor = ExecutionContext.require().actorId();
        String safeName = safeName(originalName);
        String id = UUID.randomUUID().toString().replace("-", "");
        Path target = root.resolve(id);
        return transaction.execute(status -> {
            // 补偿绑定最终事务完成状态，不能只看当前回调是否正常返回。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 数据库未提交时清理已移动的文件，避免回滚后留下无元数据文件。
                 * @param completion 事务最终状态
                 */
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
                // 在同一根目录内移动，要求文件系统支持原子移动，不静默降级为复制。
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

    /**
     * 打开当前组织成员本人上传的文件供证据类型识别和安全扫描；不允许同组织其他成员代用。
     * @param id 32 位小写十六进制文件标识
     * @return 调用方负责关闭输入流的文件元数据和内容
     * @throws BusinessException 当前身份缺少组织范围、文件不属于本人或文件不可访问
     * @throws IOException 文件属性读取或输入流打开失败
     */
    public Download openOwnedForEvidenceScan(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{32}")) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        ExecutionContext.Identity identity = ExecutionContext.require();
        long tenant = ExecutionContext.requireTenant();
        if (!"ORGANIZATION".equals(identity.contextType())) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        Metadata metadata = jdbc.query("""
                SELECT id, tenant_id, owner_id, original_name, size_bytes
                FROM foundation_file WHERE id = ? AND tenant_id = ? AND owner_id = ?
                """, result -> result.next() ? new Metadata(result.getString("id"), result.getLong("tenant_id"),
                result.getLong("owner_id"), result.getString("original_name"), result.getLong("size_bytes")) : null,
                id, tenant, identity.actorId());
        if (metadata == null) {
            audit.record("file.evidence.scan", "file", id, "DENIED");
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        Path path = root.resolve(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != metadata.sizeBytes()) {
            throw new IllegalStateException("Stored evidence file is missing or inconsistent");
        }
        InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            audit.record("file.evidence.scan", "file", id, "SUCCESS");
            return new Download(metadata, input);
        } catch (RuntimeException exception) {
            input.close();
            throw exception;
        }
    }

    /**
     * 先按当前组织查询，再校验所有者或附加策略，并检查磁盘文件一致性。
     * @param id 32 位小写十六进制文件标识
     * @return 调用方负责关闭输入流的下载对象
     * @throws BusinessException 未认证、缺少组织范围、文件不存在或读取未授权
     * @throws IllegalStateException 磁盘文件缺失或大小与元数据不一致
     * @throws IOException 文件属性读取、输入流打开或失败清理时关闭流出错
     */
    public Download open(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{32}")) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        long tenant = ExecutionContext.requireTenant();
        // 组织条件必须进入查询本身，不能依赖后续授权策略过滤跨组织元数据。
        List<Metadata> rows = jdbc.query("""
                SELECT id, tenant_id, owner_id, original_name, size_bytes FROM foundation_file WHERE id = ? AND tenant_id = ?
                """, (row, index) -> new Metadata(row.getString("id"), row.getLong("tenant_id"), row.getLong("owner_id"),
                row.getString("original_name"), row.getLong("size_bytes")), id, tenant);
        if (rows.isEmpty()) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        Metadata metadata = rows.get(0);
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (metadata.ownerId() != identity.actorId() && policies.stream().noneMatch(policy -> policy.mayRead(identity, metadata))) {
            audit.record("file.download", "file", id, "DENIED");
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        Path path = root.resolve(id);
        // 不跟随符号链接；检查大小只能发现部分不一致，不代表做过内容哈希校验。
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

    /**
     * 在不切换租户上下文的前提下打开已绑定审核事项的文件；具体授权由业务策略逐次复核。
     * 不向其他调用方开放 HTTP 路由，也不回退到文件所有者或组织成员权限。
     * @param id 32 位小写十六进制文件标识
     * @param scope 服务端从有效认领任务构造的文件访问范围
     * @return 供预览处理器消费并关闭的原始流；原件不得直接返回浏览器
     * @throws BusinessException 当前身份不满足平台文件读取约束、范围无效或策略拒绝
     * @throws IOException 文件属性读取或输入流打开失败
     */
    public Download openForPlatform(String id, PlatformFileAccessScope scope) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{32}") || scope == null || scope.evidenceId() <= 0
                || scope.taskId() <= 0 || scope.reviewCaseId() <= 0 || scope.tenantId() <= 0
                || scope.resourceId() <= 0 || scope.resourceVersion() <= 0) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        ExecutionContext.Identity identity = ExecutionContext.require();
        if (!"PLATFORM".equals(identity.contextType()) || identity.tenantId() != null) {
            throw new BusinessException(PlatformError.ACCESS_DENIED);
        }
        List<Metadata> rows = jdbc.query("""
                SELECT id, tenant_id, owner_id, original_name, size_bytes
                FROM foundation_file WHERE id = ? AND tenant_id = ?
                """, (row, index) -> new Metadata(row.getString("id"), row.getLong("tenant_id"),
                row.getLong("owner_id"), row.getString("original_name"), row.getLong("size_bytes")),
                id, scope.tenantId());
        if (rows.isEmpty()) {
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        Metadata metadata = rows.get(0);
        if (policies.stream().noneMatch(policy -> policy.mayReadForPlatform(identity, metadata, scope))) {
            audit.record("file.download", "file", id, "DENIED");
            throw new BusinessException(PlatformError.RESOURCE_NOT_FOUND);
        }
        Path path = root.resolve(id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != metadata.sizeBytes()) {
            throw new IllegalStateException("Stored file is missing or inconsistent");
        }
        InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            audit.record("file.download", "file", id, "SUCCESS", new AuditRecorder.AuditDetails(
                    Long.toString(scope.reviewCaseId()), null, null, Long.toString(scope.taskId()),
                    scope.resourceVersion(), identity.actorRole(), "EVIDENCE_PREVIEW", scope.purposeCode()));
            return new Download(metadata, input);
        } catch (RuntimeException exception) {
            input.close();
            throw exception;
        }
    }

    /**
     * 在读取期间限制总字节数，避免依赖客户端声明的文件长度。
     * @param input 调用方持有的输入流，不在此关闭
     * @param destination 临时落盘文件
     * @return 实际写入字节数
     * @throws IOException 读取或写入失败
     * @throws BusinessException 文件超过配置限制
     */
    private long copyLimited(InputStream input, Path destination) throws IOException {
        long count = 0;
        // 每次缓冲 8 KiB，不将完整上传文件加载到内存。
        byte[] buffer = new byte[8192];
        try (OutputStream output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > maxBytes) {
                    throw new BusinessException(PlatformError.PAYLOAD_TOO_LARGE);
                }
                output.write(buffer, 0, read);
            }
        }
        return count;
    }

    /**
     * 校验附件名并去除客户端路径，不把其用于磁盘存储定位。
     * @param name 客户端原始名称
     * @return 仅保留末段的非空附件名称
     * @throws IllegalArgumentException 名称为空、超过 200 字符、含控制字符或末段非法
     */
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

    /**
     * 删除临时或补偿文件，文件不存在时忽略。
     * @param path 由本存储服务生成的待清理路径
     * @throws IllegalStateException 清理失败，避免无声遗留私有文件
     */
    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Private file cleanup failed", exception);
        }
    }
}
