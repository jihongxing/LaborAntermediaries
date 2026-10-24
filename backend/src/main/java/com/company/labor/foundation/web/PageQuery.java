package com.company.labor.foundation.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 通用分页请求；接口接收时须触发 Bean Validation 才能执行页码和大小约束。 */
@Data
public class PageQuery {
    /** 从 1 开始的页码，默认 1，最小为 1。 */
    @Min(1)
    private long current = 1;
    /** 每页条数，默认 20，允许范围为 1 至 200。 */
    @Min(1)
    @Max(200)
    private long size = 20;
}
