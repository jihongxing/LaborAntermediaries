package com.company.labor.foundation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 绑定 foundation 前缀的基础能力配置，并通过嵌套校验约束关键参数。
 * 密钥、可信来源和表分类须由部署环境明确提供，不能由请求参数决定。
 */
@Data
@Validated
@ConfigurationProperties("foundation")
public class FoundationProperties {
    /** JWT 校验与跨域配置，默认创建空配置对象。 */
    @Valid
    private Security security = new Security();
    /** 加密密钥配置，默认未启用加密。 */
    @Valid
    private Crypto crypto = new Crypto();
    /** 私有文件目录与上传大小限制。 */
    @Valid
    private Storage storage = new Storage();
    /** 后台任务开关、租约与重试配置。 */
    @Valid
    private Tasks tasks = new Tasks();
    /** 全局账号认证、会话、短信与令牌签发配置。 */
    @Valid
    private Authentication authentication = new Authentication();
    /** 必须按组织隔离的表名集合，默认空集，由部署配置声明。 */
    private Set<String> tenantTables = new HashSet<>();
    /** 显式声明为共享数据的表名集合，默认空集，不应误放组织业务表。 */
    private Set<String> sharedTables = new HashSet<>();

    /** JWT 验证参数及浏览器跨域来源白名单。 */
    @Data
    public static class Security {
        /** RSA 公钥资源位置，默认空字符串；未配置时拒绝所有 JWT。 */
        private String publicKey = "";
        /** 必须匹配的签发者，默认 labor-identity，不允许空白。 */
        @NotBlank
        private String issuer = "labor-identity";
        /** JWT 必须包含的受众，默认 labor-backend，不允许空白。 */
        @NotBlank
        private String audience = "labor-backend";
        /** 允许跨域访问的明确来源，默认空集；启动检查禁止通配符。 */
        private Set<String> allowedOrigins = new HashSet<>();
    }

    /** 按版本保存的 AES 密钥集合，保留旧版本可用于解密历史数据。 */
    @Data
    public static class Crypto {
        /** 新数据使用的密钥版本，默认空字符串，非空时必须存在于 keys。 */
        private String activeKey = "";
        /** 版本到 Base64 密钥的映射，默认空；解码后只允许 16、24、32 字节。 */
        private Map<String, String> keys = new HashMap<>();
    }

    /** 不公开映射为静态资源的文件存储参数。 */
    @Data
    public static class Storage {
        /** 文件根目录，默认 ./var/private-files；生产环境必须配置绝对路径。 */
        private Path root = Path.of("./var/private-files");
        /** 单文件大小上限，单位字节，默认 10 MiB，最小为 1。 */
        @Min(1)
        private long maxBytes = 10 * 1024 * 1024;
        /** ClamAV clamd 的受控服务地址；默认空值表示禁用证据接入且必须失败关闭。 */
        private String evidenceScannerHost = "";
        /** ClamAV clamd 的 TCP 端口，默认 3310；仅允许 1 到 65535。 */
        @Min(1)
        private int evidenceScannerPort = 3310;
        /** 单次上传内容扫描超时，默认 10 秒；生产值须与网关和最大文件大小协调。 */
        private Duration evidenceScannerTimeout = Duration.ofSeconds(10);
    }

    /** 数据库任务队列参数，时间值使用 Duration 支持的配置格式。 */
    @Data
    public static class Tasks {
        /** 是否启动定时消费，默认 false；不影响显式调用 runNext。 */
        private boolean enabled;
        /** 包含首次执行的最多尝试次数，默认 5，最小为 1。 */
        @Min(1)
        private int maxAttempts = 5;
        /** 单次认领的有效租约，默认 5 分钟，必须为正；不自动续租。 */
        private Duration lease = Duration.ofMinutes(5);
        /** 失败后再次可执行的等待时间，默认 30 秒，不能为负。 */
        private Duration retryDelay = Duration.ofSeconds(30);
    }

    /**
     * 本地认证签发与安全策略；密钥只接受部署环境提供的受控资源。
     */
    @Data
    public static class Authentication {
        /** 是否启用本地账号认证端点，默认关闭直到安全密钥和本地 SMS 网关均可用。 */
        private boolean enabled;
        /** RSA PKCS#8 私钥资源位置，默认未配置；未配置时本地认证登录失败关闭。 */
        private String privateKey = "";
        /** RSA X.509 公钥资源位置，默认未配置；必须与签发私钥成对。 */
        private String publicKey = "";
        /** 本地 JWT 签发者，默认 labor-auth。 */
        @NotBlank
        private String issuer = "labor-auth";
        /** 本地 JWT 受众，默认 labor-backend。 */
        @NotBlank
        private String audience = "labor-backend";
        /** 手机号查找和认证挑战摘要使用的独立 HMAC 密钥，默认未配置。 */
        private String phoneHmacKey = "";
        /** Access Token 有效期，默认 15 分钟。 */
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        /** Refresh Session 有效期，默认 30 天。 */
        private Duration refreshSessionTtl = Duration.ofDays(30);
        /** 浏览器 Refresh Token Cookie 名称，默认 labor_refresh。 */
        @NotBlank
        private String refreshCookieName = "labor_refresh";
        /** 浏览器双提交 CSRF Cookie 名称，默认 labor_csrf。 */
        @NotBlank
        private String csrfCookieName = "labor_csrf";
        /** 是否为 Refresh Token Cookie 设置 Secure，生产默认 true。 */
        private boolean refreshCookieSecure = true;
        /** 是否允许 Mock SMS；默认 false，生产配置检查拒绝开启。 */
        private boolean mockSmsEnabled;
        /** Mock 收件箱保留期限，默认 10 分钟。 */
        private Duration mockSmsRetention = Duration.ofMinutes(10);
        /** 短信挑战有效期，默认 5 分钟。 */
        private Duration challengeTtl = Duration.ofMinutes(5);
        /** 密码重置票据有效期，默认 10 分钟。 */
        private Duration passwordResetTicketTtl = Duration.ofMinutes(10);
        /** 短信重发冷却，默认 60 秒。 */
        private Duration smsResendCooldown = Duration.ofSeconds(60);
        /** 单号码每小时最多发送短信次数，默认 5。 */
        @Min(1)
        private int smsPhoneHourlyLimit = 5;
        /** 单 IP 每小时最多发送短信次数，默认 20。 */
        @Min(1)
        private int smsIpHourlyLimit = 20;
        /** 密码登录失败窗口，默认 15 分钟。 */
        private Duration loginFailureWindow = Duration.ofMinutes(15);
        /** 失败窗口内触发临时锁定的次数，默认 5。 */
        @Min(1)
        private int loginFailureLimit = 5;
        /** 密码连续失败后的临时锁定时长，默认 15 分钟。 */
        private Duration loginLockDuration = Duration.ofMinutes(15);
        /** 挑战和重置凭证终态保留时长，默认 24 小时。 */
        private Duration challengeRetention = Duration.ofHours(24);
        /** 已撤销或过期 Refresh Session 保留时长，默认 90 天。 */
        private Duration sessionRetention = Duration.ofDays(90);
        /** 认证安全事件保留时长，默认 2 年。 */
        private Duration securityEventRetention = Duration.ofDays(730);
    }
}
