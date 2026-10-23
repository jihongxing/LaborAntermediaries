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

@RestController
@RequestMapping("/foundation/files")
public class FileController {
    private final PrivateFileStorage storage;

    public FileController(PrivateFileStorage storage) {
        this.storage = storage;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('foundation:files:write')")
    public Result<PrivateFileStorage.Metadata> upload(@RequestPart MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            return Result.success(storage.store(input, file.getOriginalFilename()));
        }
    }

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
