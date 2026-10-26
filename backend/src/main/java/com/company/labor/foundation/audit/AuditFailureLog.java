package com.company.labor.foundation.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/** 使用 JSON Lines 与强制刷盘保存数据库不可用期间的审计失败记录。 */
@Component
public class AuditFailureLog {
    /** 失败记录文件位置；生产环境应映射到受控且持久化的独立卷。 */
    private final Path path;
    /** 负责将白名单字段序列化为 JSON，不输出异常正文。 */
    private final ObjectMapper objectMapper;

    /**
     * 初始化独立故障记录文件。
     * @param objectMapper Jackson 序列化器
     * @param configuredPath 配置的 JSON Lines 文件路径
     */
    public AuditFailureLog(ObjectMapper objectMapper,
                          @Value("${audit.failure-log.path:${LOG_PATH:logs}/audit-failures.jsonl}") String configuredPath) {
        this.objectMapper = objectMapper;
        this.path = Path.of(configuredPath).toAbsolutePath().normalize();
    }

    /**
     * 将一条审计失败追加并强制刷入本地持久介质。
     * @param record 已限制为标识字段的故障记录
     * @throws IllegalStateException 目录、文件写入或刷盘失败
     */
    public synchronized void append(AuditFailureRecord record) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] json = objectMapper.writeValueAsBytes(record);
            ByteBuffer buffer = ByteBuffer.allocate(json.length + 1).put(json).put((byte) '\n');
            buffer.flip();
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 FileLock ignored = channel.lock()) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist audit failure record", exception);
        }
    }

    /**
     * 逐行读取故障文件，避免在恢复时把全部故障记录载入内存。
     * @param consumer 每条已解析记录的处理器
     * @throws IllegalStateException 文件损坏、读取失败或记录无法解析
     */
    public void readEach(Consumer<AuditFailureRecord> consumer) {
        if (!Files.exists(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    consumer.accept(objectMapper.readValue(line, AuditFailureRecord.class));
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Invalid audit failure record at line " + lineNumber, exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read audit failure log", exception);
        }
    }

    /**
     * 返回标准化后的故障文件位置，供运维说明和隔离测试定位文件。
     * @return 绝对路径
     */
    public Path path() {
        return path;
    }
}
