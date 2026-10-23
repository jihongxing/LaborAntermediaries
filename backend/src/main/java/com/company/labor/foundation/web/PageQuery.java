package com.company.labor.foundation.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PageQuery {
    @Min(1)
    private long current = 1;
    @Min(1)
    @Max(200)
    private long size = 20;
}
