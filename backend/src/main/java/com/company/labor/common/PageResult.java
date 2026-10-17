package com.company.labor.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 分页响应结果
 *
 * <p>所有分页查询接口必须返回此格式。
 *
 * <p>响应示例：
 * <pre>
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": {
 *     "records": [...],        // 数据列表
 *     "total": 100,            // 总记录数
 *     "current": 1,            // 当前页码
 *     "size": 20,              // 每页大小
 *     "pages": 5               // 总页数
 *   },
 *   "timestamp": 1697520000000
 * }
 * </pre>
 *
 * <p>使用示例：
 * <pre>
 * // 方式1：从MyBatis Plus的Page对象转换
 * Page<Employee> page = employeeMapper.selectPage(new Page<>(1, 20), null);
 * return Result.success(PageResult.of(page));
 *
 * // 方式2：手动构建
 * List<Employee> records = ...;
 * PageResult<Employee> pageResult = new PageResult<>(records, 100, 1, 20);
 * return Result.success(pageResult);
 * </pre>
 *
 * @param <T> 数据类型
 * @author 技术团队
 * @since 2026-10-18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResult<T> {

    /**
     * 数据列表
     */
    private List<T> records;

    /**
     * 总记录数
     */
    private Long total;

    /**
     * 当前页码（从1开始）
     */
    private Long current;

    /**
     * 每页大小
     */
    private Long size;

    /**
     * 总页数
     */
    private Long pages;

    /**
     * 无参构造函数
     */
    public PageResult() {
    }

    /**
     * 构造函数
     *
     * @param records 数据列表
     * @param total 总记录数
     * @param current 当前页码
     * @param size 每页大小
     */
    public PageResult(List<T> records, Long total, Long current, Long size) {
        this.records = records;
        this.total = total;
        this.current = current;
        this.size = size;
        this.pages = calculatePages(total, size);
    }

    /**
     * 从MyBatis Plus的Page对象转换
     *
     * @param page MyBatis Plus的Page对象
     * @param <T> 数据类型
     * @return PageResult
     */
    public static <T> PageResult<T> of(com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page) {
        return new PageResult<>(
                page.getRecords(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize()
        );
    }

    /**
     * 空分页结果
     *
     * @param <T> 数据类型
     * @return 空的PageResult
     */
    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), 0L, 1L, 20L);
    }

    /**
     * 计算总页数
     *
     * @param total 总记录数
     * @param size 每页大小
     * @return 总页数
     */
    private Long calculatePages(Long total, Long size) {
        if (total == null || total == 0 || size == null || size == 0) {
            return 0L;
        }
        return (total + size - 1) / size;
    }

    /**
     * 是否有下一页
     *
     * @return true有下一页，false没有
     */
    public boolean hasNext() {
        return current != null && pages != null && current < pages;
    }

    /**
     * 是否有上一页
     *
     * @return true有上一页，false没有
     */
    public boolean hasPrevious() {
        return current != null && current > 1;
    }
}
