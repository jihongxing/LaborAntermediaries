package com.company.labor.common.resource;

/**
 * 跨组织协作使用的资源引用，只承载标识，不声明所有者或参与方。
 * @param type 由所属业务模块登记的资源类型，例如 demand 或 agreement
 * @param id 该类型下全平台唯一的非敏感资源标识，不能仅使用组织内编号
 */
public record ResourceRef(String type, String id) {
    /**
     * 限制标识格式，防止将敏感正文作为审计或授权键传入。
     * @param type 最多 64 位的小写类型标识
     * @param id 最多 128 位的资源标识
     * @throws IllegalArgumentException 标识为空或格式不合法
     */
    public ResourceRef {
        if (type == null || !type.matches("[a-z][a-z0-9_.-]{0,63}")
                || id == null || !id.matches("[a-zA-Z0-9:_-]{1,128}")) {
            throw new IllegalArgumentException("Resource reference must contain registered identifiers");
        }
    }
}
