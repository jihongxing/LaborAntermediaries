package com.company.labor.foundation.storage;

import java.io.IOException;
import java.io.InputStream;

/** 私有证据进入审核范围前的内容扫描边界；任何不可用或未知扫描结果都不得按通过处理。 */
public interface EvidenceContentScanner {
    /**
     * 将文件流交给受控恶意内容扫描器并计算被扫描字节的 SHA-256。
     * @param content 由存储服务打开的文件流；调用方负责关闭
     * @return 明确的扫描结果及内容摘要
     * @throws IOException 扫描器不可用、超时、协议错误或输入读取失败
     */
    ScanResult scan(InputStream content) throws IOException;

    /** 扫描器返回的内容判定和传输中计算的完整摘要。 */
    record ScanResult(boolean clean, String sha256) {
        /**
         * 校验扫描结果摘要格式。
         * @param clean 扫描器是否明确返回无威胁
         * @param sha256 被扫描内容的小写 SHA-256
         */
        public ScanResult {
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid evidence digest");
            }
        }
    }
}
