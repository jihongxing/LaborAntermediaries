package com.company.labor.modules.platform;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/** 将已扫描原件转换成去除活动内容和元数据的单页 PNG；绝不向浏览器返回原始文件。 */
@Service
public class ReviewEvidencePreviewRenderer {
    /** 单边像素硬上限，避免异常页面或图像尺寸造成大内存分配。 */
    private static final int MAX_DIMENSION = 4096;
    /** 单页栅格化像素数硬上限。 */
    private static final long MAX_PIXELS = 16_000_000;
    /** PDF 单页预览分辨率，单位 DPI。 */
    private static final float PDF_DPI = 96;
    /** PDF 可预览页数硬上限。 */
    private static final int MAX_PDF_PAGES = 200;

    /**
     * 解码一页允许的文件并重新编码为不含源元数据的 PNG。
     * @param source 已经验证摘要且未超过存储大小限制的原始字节
     * @param fileType 服务端按内容魔数识别的文件类型
     * @param pageIndex PDF 零起始页码；图片必须为零
     * @return 脱离原始文档结构的 PNG 字节
     * @throws IOException 文档损坏、页码不合法或安全解码失败
     * @throws IllegalArgumentException 图像或页面超出栅格化限制
     */
    public byte[] render(byte[] source, ReviewEvidenceFileType fileType, int pageIndex) throws IOException {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("Invalid evidence page");
        }
        BufferedImage raster = fileType == ReviewEvidenceFileType.PDF
                ? renderPdf(source, pageIndex) : renderImage(source, fileType, pageIndex);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(raster, "png", output)) {
                throw new IOException("PNG preview encoder is unavailable");
            }
            return output.toByteArray();
        }
    }

    /**
     * 以固定分辨率渲染 PDF 单页并限制文件页数、单边大小和总像素。
     * @param source PDF 原始字节
     * @param pageIndex 零起始页码
     * @return 页面栅格
     * @throws IOException PDF 无效、加密或页码不存在
     */
    private BufferedImage renderPdf(byte[] source, int pageIndex) throws IOException {
        try (PDDocument document = Loader.loadPDF(source)) {
            int pages = document.getNumberOfPages();
            if (document.isEncrypted() || pages < 1 || pages > MAX_PDF_PAGES || pageIndex >= pages) {
                throw new IOException("Evidence PDF cannot be previewed");
            }
            PDRectangle box = document.getPage(pageIndex).getMediaBox();
            verifyDimensions(Math.ceil(box.getWidth() * PDF_DPI / 72),
                    Math.ceil(box.getHeight() * PDF_DPI / 72));
            return new PDFRenderer(document).renderImageWithDPI(pageIndex, PDF_DPI, ImageType.RGB);
        }
    }

    /**
     * 按解码器报告的尺寸先限幅，再读取像素并重新绘制，去除原图 EXIF/文本元数据。
     * @param source 图片原始字节
     * @param fileType 魔数识别出的 JPEG 或 PNG 类型
     * @param pageIndex 图片请求页码
     * @return 不包含源元数据的 RGB 栅格
     * @throws IOException 图像无效、格式与魔数不符或解码器不可用
     */
    private BufferedImage renderImage(byte[] source, ReviewEvidenceFileType fileType, int pageIndex)
            throws IOException {
        if (pageIndex != 0) {
            throw new IOException("Image evidence has one preview page");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Evidence image cannot be decoded");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!(fileType == ReviewEvidenceFileType.JPEG && "JPEG".equalsIgnoreCase(format)
                        || fileType == ReviewEvidenceFileType.PNG && "PNG".equalsIgnoreCase(format))) {
                    throw new IOException("Evidence image type does not match its content");
                }
                verifyDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage decoded = reader.read(0);
                BufferedImage clean = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = clean.createGraphics();
                try {
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                decoded.flush();
                return clean;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 拒绝超出单边或总像素上限的图片和 PDF 页面。
     * @param width 解码前可取得的宽度像素
     * @param height 解码前可取得的高度像素
     * @throws IllegalArgumentException 非法或过大的尺寸
     */
    private void verifyDimensions(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 1 || height < 1
                || width > MAX_DIMENSION || height > MAX_DIMENSION || width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("Evidence preview dimensions exceed limits");
        }
    }
}
