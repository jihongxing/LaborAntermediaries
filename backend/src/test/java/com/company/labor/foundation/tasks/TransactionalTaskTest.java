package com.company.labor.foundation.tasks;

import com.company.labor.foundation.FoundationProperties;
import com.company.labor.foundation.context.ExecutionContext;
import com.company.labor.foundation.web.JsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证可靠投递入口必须绑定事务，不以单独提交任务冒充业务与投递同提交。 */
class TransactionalTaskTest {
    /** 模拟任务数据库写入。 */
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    /**
     * 创建带已注册测试处理器的任务队列。
     * @return 无后台轮询的测试队列
     */
    private TaskQueue queue() {
        TaskHandler handler = mock(TaskHandler.class);
        when(handler.type()).thenReturn("notification.fixture");
        return new TaskQueue(jdbc, new JsonCodec(new ObjectMapper()), new FoundationProperties(),
                List.of(handler), mock(PlatformTransactionManager.class));
    }

    /** 无活动事务时立即拒绝，不能先提交任务再期待业务提交成功。 */
    @Test
    void transactionIsMandatoryBeforeEnqueue() {
        TaskQueue queue = queue();
        assertThrows(IllegalTransactionStateException.class,
                () -> queue.enqueueTransactional("notification.fixture", Map.of("resourceId", "1")));
        verifyNoInteractions(jdbc);
    }

    /** 活动事务内沿用原有队列写入逻辑，仍记录真实提交组织和操作者。 */
    @Test
    void transactionalEnqueuePreservesTrustedIdentity() {
        TaskQueue queue = queue();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(123L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var scope = ExecutionContext.open(new ExecutionContext.Identity(7, 11L, Set.of()), "task-test")) {
            assertEquals(123, queue.enqueueTransactional("notification.fixture", Map.of("resourceId", "1")));
            verify(jdbc).queryForObject(anyString(), eq(Long.class), eq(11L), eq(7L),
                    eq("notification.fixture"), eq("{\"resourceId\":\"1\"}"), eq("task-test"));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
