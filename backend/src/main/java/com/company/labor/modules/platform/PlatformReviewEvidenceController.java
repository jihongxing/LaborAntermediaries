package com.company.labor.modules.platform;

import com.company.labor.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供当前岗位有效认领任务所需的材料摘要和安全栅格化预览。 */
@RestController
@RequestMapping("/platform/tasks/{taskId}/evidence")
public class PlatformReviewEvidenceController {
    /** 每个请求均重新验证岗位、认领、版本、回避和材料绑定。 */
    private final PlatformReviewEvidenceService evidence;

    /**
     * 创建平台任务证据接口。
     * @param evidence 材料授权、完整性校验和预览服务
     */
    public PlatformReviewEvidenceController(PlatformReviewEvidenceService evidence) {
        this.evidence = evidence;
    }

    /**
     * 读取本人有效认领任务所需的材料最小摘要。
     * @param taskId 当前岗位任务
     * @return 任务绑定版本中的已扫描材料
     */
    @GetMapping
    public Result<List<PlatformReviewEvidenceService.PlatformEvidenceView>> list(@PathVariable long taskId) {
        return Result.success(evidence.listForTask(taskId));
    }

    /**
     * Reads itemized completeness, authenticity and validity checks for this task version.
     * @param taskId claimed review task identifier
     * @return append-only verification trail
     */
    @GetMapping("/verifications")
    public Result<List<PlatformReviewEvidenceService.VerificationView>> verifications(@PathVariable long taskId) {
        return Result.success(evidence.listVerifications(taskId));
    }

    /**
     * Appends an itemized verification result for one material in the claimed task.
     * @param taskId claimed review task identifier
     * @param evidenceId material identifier
     * @param request verification source and result
     * @return appended verification record
     */
    @PostMapping("/{evidenceId}/verifications")
    public Result<PlatformReviewEvidenceService.VerificationView> recordVerification(
            @PathVariable long taskId, @PathVariable long evidenceId,
            @Valid @RequestBody PlatformReviewEvidenceService.VerificationRequest request) {
        return Result.success(evidence.recordVerification(taskId, evidenceId, request));
    }

    /**
     * 返回单页脱离原始格式和元数据的 PNG；禁止缓存并阻止浏览器 MIME 推断。
     * @param taskId 当前岗位任务
     * @param evidenceId 当前任务版本内的证据编号
     * @param page 零起始 PDF 页码；图片仅接受零
     * @return 无原件下载路径的 PNG 页面
     */
    @GetMapping("/{evidenceId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable long taskId, @PathVariable long evidenceId,
                                          @RequestParam(defaultValue = "0") int page) {
        byte[] content = evidence.preview(taskId, evidenceId, page);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(content.length)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Referrer-Policy", "no-referrer")
                .body(content);
    }
}
