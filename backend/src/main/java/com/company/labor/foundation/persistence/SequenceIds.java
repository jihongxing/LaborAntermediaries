package com.company.labor.foundation.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 使用 PostgreSQL 共享序列生成标识；序列不保证连续，事务回滚也可能产生间隙。 */
@Service
public class SequenceIds {
    /** 执行数据库序列查询的模板。 */
    private final JdbcTemplate jdbc;

    /**
     * 创建序列标识服务。
     * @param jdbc 数据库访问模板
     */
    public SequenceIds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 从 foundation_id_seq 获取下一个标识。
     * @return 数据库分配的序列值
     * @throws IllegalStateException 数据库未返回序列值
     */
    public long nextId() {
        Long value = jdbc.queryForObject("SELECT nextval('foundation_id_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("Identifier sequence returned no value");
        }
        return value;
    }

    /**
     * 生成可读引用号，仅用于标识，不承诺连续编号。
     * @param prefix 1 至 16 位大写字母或数字，首位必须为字母
     * @return 前缀、连字符和序列值组成的引用号
     * @throws IllegalArgumentException 前缀格式不合法
     */
    public String nextReference(String prefix) {
        if (prefix == null || !prefix.matches("[A-Z][A-Z0-9]{0,15}")) {
            throw new IllegalArgumentException("Reference prefix must contain 1-16 uppercase letters or digits");
        }
        return prefix + "-" + nextId();
    }
}
