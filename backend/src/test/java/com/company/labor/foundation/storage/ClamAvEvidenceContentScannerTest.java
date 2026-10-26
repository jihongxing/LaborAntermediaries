package com.company.labor.foundation.storage;

import com.company.labor.foundation.FoundationProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 ClamAV INSTREAM framing、固定判定、摘要计算和故障关闭。 */
class ClamAvEvidenceContentScannerTest {
    /** 验证扫描器发送完整长度分块且对明确 OK 返回内容摘要。
     * @throws Exception 本地协议服务或并发结果失败
     */
    @Test
    void sendsInstreamChunksAndReturnsDigestForCleanContent() throws Exception {
        byte[] content = "synthetic clean evidence".getBytes(StandardCharsets.UTF_8);
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
            Future<byte[]> received = executor.submit(() -> serve(server, "stream: OK\0"));
            EvidenceContentScanner.ScanResult result = scanner(server, Duration.ofSeconds(2))
                    .scan(new ByteArrayInputStream(content));
            assertTrue(result.clean());
            assertEquals(sha256(content), result.sha256());
            assertArrayEquals(content, received.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** 验证恶意判定被作为扫描结果拒绝而不泄露扫描器特征名称。
     * @throws Exception 本地协议服务或并发结果失败
     */
    @Test
    void returnsNotCleanForFoundVerdict() throws Exception {
        byte[] content = "synthetic malware marker".getBytes(StandardCharsets.UTF_8);
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
            Future<byte[]> received = executor.submit(() -> serve(server, "stream: Sensitive.Test.Name FOUND\0"));
            EvidenceContentScanner.ScanResult result = scanner(server, Duration.ofSeconds(2))
                    .scan(new ByteArrayInputStream(content));
            assertFalse(result.clean());
            assertEquals(sha256(content), result.sha256());
            assertArrayEquals(content, received.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** 验证未知、截断和未配置的扫描器状态均失败关闭。
     * @throws Exception 本地协议服务或并发结果失败
     */
    @Test
    void failsClosedForUnknownIncompleteAndUnconfiguredScanner() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
            Future<byte[]> received = executor.submit(() -> serve(server, "stream: ERROR\0"));
            assertThrows(IOException.class, () -> scanner(server, Duration.ofSeconds(2))
                    .scan(new ByteArrayInputStream(new byte[]{1, 2, 3})));
            assertArrayEquals(new byte[]{1, 2, 3}, received.get());
            } finally {
                executor.shutdownNow();
            }
        }
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
            Future<byte[]> received = executor.submit(() -> serve(server, "stream: OK"));
            assertThrows(IOException.class, () -> scanner(server, Duration.ofSeconds(2))
                    .scan(new ByteArrayInputStream(new byte[]{4, 5})));
            assertArrayEquals(new byte[]{4, 5}, received.get());
            } finally {
                executor.shutdownNow();
            }
        }
        FoundationProperties properties = new FoundationProperties();
        assertThrows(IOException.class, () -> new ClamAvEvidenceContentScanner(properties)
                .scan(new ByteArrayInputStream(new byte[0])));
    }

    /** 验证不合法端口和扫描超时不能被配置为可用扫描能力。
     * @throws Exception 本地协议服务或并发结果失败
     */
    @Test
    void rejectsInvalidConfigurationAndReadTimeout() throws Exception {
        FoundationProperties invalid = new FoundationProperties();
        invalid.getStorage().setEvidenceScannerPort(0);
        assertThrows(IllegalArgumentException.class, () -> new ClamAvEvidenceContentScanner(invalid));

        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
            Future<byte[]> received = executor.submit(() -> serve(server, null));
            assertThrows(IOException.class, () -> scanner(server, Duration.ofMillis(100))
                    .scan(new ByteArrayInputStream(new byte[]{7})));
            assertArrayEquals(new byte[]{7}, received.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** 启动一次真实 TCP 协议交互并返回服务端接收的原始数据。
     * @param server 本地临时端口
     * @param response 完整或刻意截断的 NUL 结尾扫描器响应；为空时保持连接直到客户端超时
     * @return 客户端按协议发送的原始内容
     * @throws IOException 连接或协议读取失败
     */
    private byte[] serve(ServerSocket server, String response) throws IOException {
        try (Socket socket = server.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            byte[] command = input.readNBytes(10);
            if (!"zINSTREAM\0".equals(new String(command, StandardCharsets.US_ASCII))) {
                throw new IOException("Unexpected ClamAV command");
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            int chunkLength;
            while ((chunkLength = input.readInt()) > 0) {
                content.write(input.readNBytes(chunkLength));
            }
            if (response != null) {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
            return content.toByteArray();
        }
    }

    /** 从本地服务端口创建有限超时的扫描器实例。
     * @param server 接收 INSTREAM 请求的本地服务端
     * @param timeout socket 建连和读取时限
     * @return 指向临时服务端口的扫描器
     */
    private ClamAvEvidenceContentScanner scanner(ServerSocket server, Duration timeout) {
        FoundationProperties properties = new FoundationProperties();
        properties.getStorage().setEvidenceScannerHost("127.0.0.1");
        properties.getStorage().setEvidenceScannerPort(server.getLocalPort());
        properties.getStorage().setEvidenceScannerTimeout(timeout);
        return new ClamAvEvidenceContentScanner(properties);
    }

    /** 计算测试内容的标准 SHA-256。
     * @param content 已发送给临时扫描器的原始字节
     * @return 小写十六进制摘要
     * @throws Exception Java 标准摘要服务不可用
     */
    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
