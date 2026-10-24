package com.company.labor.foundation.tasks;

/**
 * 数据库任务处理扩展点；租约到期或失败可能导致重试，处理器必须自行保证业务幂等。
 * 后台仅恢复操作者与组织，不继承原请求权限，所需授权应由业务明确设计。
 */
public interface TaskHandler {
    /**
     * 返回处理器唯一任务类型，启动时按该值注册。
     * @return 最长 100 字符、以小写字母开头的类型标识
     */
    String type();

    /**
     * 执行一次任务尝试，不保证只调用一次。
     * @param taskId 持久化任务标识，可用于业务去重
     * @param payload 入队时保存的 JSON 数据，处理器负责解析与校验
     * @throws Exception 执行失败，由队列记录异常类型并按上限决定重试
     */
    void execute(long taskId, String payload) throws Exception;
}
