package com.company.labor.foundation.storage;

import com.company.labor.foundation.context.ExecutionContext;

/** 扩展同组织内非所有者的文件读取授权；组织隔离由存储服务先行执行，不能由策略放宽。 */
public interface FileAccessPolicy {
    /**
     * 判断当前身份能否读取已按组织筛选的文件；所有者由存储服务直接允许。
     * @param identity 当前已认证身份
     * @param file 当前组织中的文件元数据
     * @return 允许读取时为 true；多个策略中任一个允许即放行
     */
    boolean mayRead(ExecutionContext.Identity identity, PrivateFileStorage.Metadata file);

    /**
     * 判断平台审核任务是否可以读取明确绑定的组织材料；默认拒绝平台跨组织读取。
     * @param identity 当前平台工作身份
     * @param file 已按可信任务租户范围筛选的文件元数据
     * @param scope 文件所属审核事项、对象版本和认领任务
     * @return 仅在具体业务策略确认当前授权、任务和证据绑定均有效时为 true
     */
    default boolean mayReadForPlatform(ExecutionContext.Identity identity, PrivateFileStorage.Metadata file,
                                       PlatformFileAccessScope scope) {
        return false;
    }
}
