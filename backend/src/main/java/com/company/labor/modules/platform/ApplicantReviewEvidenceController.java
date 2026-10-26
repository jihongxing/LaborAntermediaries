package com.company.labor.modules.platform;

import com.company.labor.common.Result;
import com.company.labor.modules.platform.ApplicantReviewEvidenceDtos.AttachRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供经组织成员和资源版本授权的申请方材料绑定及引用查询接口。 */
@RestController
@RequestMapping("/review-evidence")
public class ApplicantReviewEvidenceController {
    /** 执行资源权限、版本状态、扫描、绑定及审计检查的领域服务。 */
    private final PlatformReviewEvidenceService evidence;

    /**
     * 创建申请方证据接口。
     * @param evidence 材料绑定和查询服务
     */
    public ApplicantReviewEvidenceController(PlatformReviewEvidenceService evidence) {
        this.evidence = evidence;
    }

    /**
     * 扫描并绑定当前组织申请版本材料。
     * @param organizationId 当前组织编号
     * @param request 文件编号、预期版本和申请方声明类别
     * @return 新增材料的最小引用
     */
    @PostMapping("/organizations/{organizationId}")
    public Result<PlatformReviewEvidenceService.ApplicantEvidenceView> attachOrganization(
            @PathVariable long organizationId, @Valid @RequestBody AttachRequest request) {
        return Result.success(evidence.attach(ReviewCaseType.ORGANIZATION, organizationId,
                request.expectedVersion(), request.fileId(), request.category()));
    }

    /**
     * 按版本读取当前组织管理员可见的材料引用。
     * @param organizationId 当前组织编号
     * @param version 申请版本
     * @return 当前组织材料摘要
     */
    @GetMapping("/organizations/{organizationId}")
    public Result<List<PlatformReviewEvidenceService.ApplicantEvidenceView>> listOrganization(
            @PathVariable long organizationId, @RequestParam long version) {
        return Result.success(evidence.listForApplicant(ReviewCaseType.ORGANIZATION, organizationId, version));
    }

    /**
     * 扫描并绑定当前需求版本材料。
     * @param demandId 当前需求编号
     * @param request 文件编号、预期版本和申请方声明类别
     * @return 新增材料的最小引用
     */
    @PostMapping("/demands/{demandId}")
    public Result<PlatformReviewEvidenceService.ApplicantEvidenceView> attachDemand(
            @PathVariable long demandId, @Valid @RequestBody AttachRequest request) {
        return Result.success(evidence.attach(ReviewCaseType.DEMAND, demandId,
                request.expectedVersion(), request.fileId(), request.category()));
    }

    /**
     * 按版本读取当前需求编辑员可见的材料引用。
     * @param demandId 当前需求编号
     * @param version 需求修订号
     * @return 当前需求材料摘要
     */
    @GetMapping("/demands/{demandId}")
    public Result<List<PlatformReviewEvidenceService.ApplicantEvidenceView>> listDemand(
            @PathVariable long demandId, @RequestParam long version) {
        return Result.success(evidence.listForApplicant(ReviewCaseType.DEMAND, demandId, version));
    }
}
