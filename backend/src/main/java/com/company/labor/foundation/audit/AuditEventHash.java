package com.company.labor.foundation.audit;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** 对持久化审计字段及前序摘要生成稳定哈希。 */
final class AuditEventHash {
    /** 禁止实例化哈希工具类。 */
    private AuditEventHash() {
    }

    /**
     * 对带版本号和长度前缀的二进制表示计算 SHA-256，确保空值与分隔符不产生歧义。
     * @param event 待核对或追加的审计事件字段
     * @return 小写十六进制 SHA-256 摘要
     * @throws IllegalStateException 摘要算法不可用或字段编码失败
     */
    static String calculate(Event event) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                writeString(data, "foundation-audit-v1");
                data.writeLong(event.id());
                writeLong(data, event.actorId());
                writeString(data, event.actorType());
                writeLong(data, event.tenantId());
                writeString(data, event.action());
                writeString(data, event.resourceType());
                writeString(data, event.resourceId());
                writeString(data, event.outcome());
                writeString(data, event.requestId());
                data.writeLong(event.createdAt().getEpochSecond());
                data.writeInt(event.createdAt().getNano());
                writeString(data, event.reviewCaseId());
                writeString(data, event.appealCaseId());
                writeString(data, event.findingId());
                writeString(data, event.taskId());
                writeLong(data, event.resourceVersion());
                writeString(data, event.actorRole());
                writeString(data, event.correlationId());
                writeString(data, event.reasonCode());
                writeString(data, event.purposeCode());
                writeString(data, event.previousHash());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode audit event", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 写入可空数字并显式编码空值标记。
     * @param data 目标二进制流
     * @param value 可空数字
     * @throws IOException 二进制写入失败
     */
    private static void writeLong(DataOutputStream data, Long value) throws IOException {
        data.writeBoolean(value != null);
        if (value != null) {
            data.writeLong(value);
        }
    }

    /**
     * 写入带长度前缀的可空 UTF-8 字符串。
     * @param data 目标二进制流
     * @param value 可空字符串
     * @throws IOException 二进制写入失败
     */
    private static void writeString(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(encoded.length);
        data.write(encoded);
    }

    /**
     * 追加和完整性核对共同使用的审计持久化字段。
     * @param id 事件编号
     * @param actorId 操作账号
     * @param actorType 身份类型
     * @param tenantId 组织范围
     * @param action 操作码
     * @param resourceType 资源类型
     * @param resourceId 资源标识
     * @param outcome 操作结果
     * @param requestId 请求标识
     * @param createdAt 数据库创建时间
     * @param reviewCaseId 审核事项标识
     * @param appealCaseId 异议复核标识
     * @param findingId 监察发现标识
     * @param taskId 岗位任务标识
     * @param resourceVersion 资源版本
     * @param actorRole 操作时的平台岗位
     * @param correlationId 请求关联标识
     * @param reasonCode 稳定原因码
     * @param purposeCode 读取用途码
     * @param previousHash 前序事件摘要
     */
    record Event(
            long id,
            Long actorId,
            String actorType,
            Long tenantId,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String requestId,
            Instant createdAt,
            String reviewCaseId,
            String appealCaseId,
            String findingId,
            String taskId,
            Long resourceVersion,
            String actorRole,
            String correlationId,
            String reasonCode,
            String purposeCode,
            String previousHash) {
    }
}
