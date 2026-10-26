package com.company.labor.modules.platform;

import java.io.IOException;
import java.io.InputStream;

/** 只按文件内容签名识别允许进入审核流程的文件类型，不信任扩展名或请求 MIME。 */
public enum ReviewEvidenceFileType {
    /** 可通过栅格化页面预览的 PDF 文件。 */
    PDF("application/pdf"),
    /** 重新编码后预览的 JPEG 图片。 */
    JPEG("image/jpeg"),
    /** 重新编码后预览的 PNG 图片。 */
    PNG("image/png");

    /** 数据库中稳定保存的媒体类型代码。 */
    private final String mediaType;

    /**
     * 声明文件内容类型。
     * @param mediaType 标准媒体类型
     */
    ReviewEvidenceFileType(String mediaType) {
        this.mediaType = mediaType;
    }

    /** @return 由服务端识别的标准媒体类型 */
    public String mediaType() {
        return mediaType;
    }

    /**
     * 从数据库保存的服务端 MIME 类型恢复预览解码器类型。
     * @param mediaType 已通过上传时魔数检查并持久化的媒体类型
     * @return 对应的有限文件类型
     * @throws IllegalArgumentException 类型不在首期安全预览白名单
     */
    public static ReviewEvidenceFileType fromMediaType(String mediaType) {
        for (ReviewEvidenceFileType type : values()) {
            if (type.mediaType.equals(mediaType)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported stored evidence media type");
    }

    /**
     * 读取并恢复有限长度的文件头，根据魔数拒绝 SVG、HTML、压缩包和未知内容。
     * @param input 支持 mark/reset 的缓冲输入流
     * @return 支持安全预览的内容类型
     * @throws IOException 输入读取失败
     * @throws IllegalArgumentException 文件头不属于允许的类型
     */
    public static ReviewEvidenceFileType detect(InputStream input) throws IOException {
        input.mark(16);
        byte[] header = input.readNBytes(8);
        input.reset();
        if (header.length >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D'
                && header[3] == 'F' && header[4] == '-') {
            return PDF;
        }
        if (header.length == 8 && (header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N'
                && header[3] == 'G' && (header[4] & 0xff) == 0x0d && (header[5] & 0xff) == 0x0a
                && (header[6] & 0xff) == 0x1a && (header[7] & 0xff) == 0x0a) {
            return PNG;
        }
        if (header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff) {
            return JPEG;
        }
        throw new IllegalArgumentException("Unsupported evidence content type");
    }
}
