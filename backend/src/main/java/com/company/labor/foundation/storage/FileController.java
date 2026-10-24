package com.company.labor.foundation.storage;

import com.company.labor.common.Result;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 提供需认证及读写权限的私有文件接口，具体组织与资源授权交由存储服务校验。 */
@RestController
@RequestMapping("/foundation/files")
public class FileController {
    /** 执行文件隔离、授权、流式大小校验与审计的存储服务。 */
    private final PrivateFileStorage storage;

    /**
     * 创建私有文件接口。
     * @param storage 私有文件存储服务
     */
    public FileController(PrivateFileStorage storage) {
        this.storage = storage;
    }

    /**
     * 上传文件并绑定当前组织及操作者；读取完成后关闭 multipart 输入流。
     * @param file 待上传文件
     * @return 已持久化文件的元数据
     * @throws IOException multipart 输入流打开或关闭失败
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('foundation:files:write')")
    public Result<PrivateFileStorage.Metadata> upload(@RequestPart MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            return Result.success(storage.store(input, file.getOriginalFilename()));
        }
    }

    /**
     * 授权后以附件下载文件，禁用缓存；成功返回的流交给响应资源处理器消费和关闭。
     * @param id 文件标识
     * @return 二进制附件响应
     * @throws IOException 文件校验或输入流打开失败
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('foundation:files:read')")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id) throws IOException {
        PrivateFileStorage.Download download = storage.open(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.metadata().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.metadata().originalName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(download.stream()));
    }
}
