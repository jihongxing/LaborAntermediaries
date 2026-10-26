package com.company.labor.foundation.storage;

import com.company.labor.foundation.FoundationProperties;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** 通过 clamd 的 INSTREAM 协议扫描组织证据；未配置服务、协议不确定或超时一律失败关闭。 */
@Service
public class ClamAvEvidenceContentScanner implements EvidenceContentScanner {
    /** 经部署环境配置的 clamd 主机名；空值明确表示扫描能力未启用。 */
    private final String host;
    /** clamd TCP 端口。 */
    private final int port;
    /** 建连和扫描响应的最长等待时间。 */
    private final Duration timeout;

    /**
     * 创建扫描适配器。
     * @param properties 私有存储和扫描器配置
     */
    public ClamAvEvidenceContentScanner(FoundationProperties properties) {
        String configuredHost = properties.getStorage().getEvidenceScannerHost();
        this.host = configuredHost == null ? "" : configuredHost.trim();
        this.port = properties.getStorage().getEvidenceScannerPort();
        this.timeout = properties.getStorage().getEvidenceScannerTimeout();
        if (port < 1 || port > 65535 || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Invalid evidence scanner configuration");
        }
    }

    /**
     * 以定长数据块流式发送文件，不将扫描器返回的特征名称写入响应或日志。
     * @param content 由调用方持有并负责关闭的文件流
     * @return 扫描判定和与同一数据流绑定的摘要
     * @throws IOException 扫描器未配置、连接失败、超时、返回不确定状态或输入读取失败
     */
    @Override
    public ScanResult scan(InputStream content) throws IOException {
        if (host.isBlank()) {
            throw new IOException("Evidence content scanner is not configured");
        }
        int timeoutMillis = Math.toIntExact(Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        MessageDigest digest = sha256();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream response = new DataInputStream(socket.getInputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[8192];
            int length;
            while ((length = content.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
                output.writeInt(length);
                output.write(buffer, 0, length);
            }
            output.writeInt(0);
            output.flush();
            String verdict = readVerdict(response);
            if ("OK".equals(verdict)) {
                return new ScanResult(true, HexFormat.of().formatHex(digest.digest()));
            }
            if ("FOUND".equals(verdict)) {
                return new ScanResult(false, HexFormat.of().formatHex(digest.digest()));
            }
            throw new IOException("Evidence scanner returned an indeterminate verdict");
        }
    }

    /**
     * 读取有限长度的 NUL 结尾协议响应，只保留固定判定，不保存特征名称。
     * @param input clamd 响应流
     * @return OK、FOUND 或 ERROR
     * @throws IOException 响应格式错误或超过长度上限
     */
    private String readVerdict(DataInputStream input) throws IOException {
        byte[] response = new byte[1024];
        int length = 0;
        int value;
        while ((value = input.read()) != -1 && value != 0) {
            if (length == response.length) {
                throw new IOException("Evidence scanner response is too long");
            }
            response[length++] = (byte) value;
        }
        if (value == -1 || length == 0) {
            throw new IOException("Evidence scanner response is incomplete");
        }
        String text = new String(response, 0, length, StandardCharsets.US_ASCII);
        if (text.endsWith(": OK")) {
            return "OK";
        }
        if (text.startsWith("stream: ") && text.endsWith(" FOUND")) {
            return "FOUND";
        }
        return "ERROR";
    }

    /**
     * 创建 SHA-256 摘要器；Java 运行时必须提供标准算法。
     * @return 可独立更新的摘要器
     * @throws IllegalStateException 当前运行时没有标准 SHA-256 算法
     */
    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
