package com.company.labor.foundation.access;

import com.company.labor.common.resource.ResourceRef;
import com.company.labor.foundation.context.ExecutionContext;

/**
 * 业务模块提供的资源级授权策略；底座不推断交易参与方或平台监督权。
 * 实现必须从可信存储校验当前成员、有效参与关系、操作及字段用途，不信任客户端提供的组织列表。
 */
public interface ResourceAccessPolicy {
    /**
     * 声明本策略唯一负责的资源类型。
     * @return 与 ResourceRef.type 一致的类型
     */
    String resourceType();

    /**
     * 在基础操作权限通过后，检查该身份此刻是否可操作指定资源。
     * 不存在、已撤销或超范围的关系均返回 false；存储异常必须传播，不能降级放行。
     * @param identity 当前可信身份，不可由调用方切换成资源所有者
     * @param resource 仅含类型和标识的资源引用
     * @param permission 当前请求的精确权限标识
     * @return 当前资源级授权通过时为 true
     */
    boolean mayAccess(ExecutionContext.Identity identity, ResourceRef resource, String permission);
}
