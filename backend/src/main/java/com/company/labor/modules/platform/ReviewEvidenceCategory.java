package com.company.labor.modules.platform;

/** 申请方提交的通用材料分类；分类不代表真实性或法定资质结论。 */
public enum ReviewEvidenceCategory {
    /** 组织控制权、法定代表或经办授权材料。 */
    AUTHORIZATION,
    /** 适用资质、备案或许可材料。 */
    QUALIFICATION,
    /** 用于证明需求或申请内容的补充文件。 */
    SUPPORTING_DOCUMENT,
    /** 无法归入其他类别的申请材料。 */
    OTHER
}
