package com.company.labor.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantContext单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("租户上下文测试")
class TenantContextTest {

    @AfterEach
    void tearDown() {
        // 每个测试后清除租户ID
        TenantContext.clear();
    }

    @Test
    @DisplayName("设置和获取租户ID")
    void testSetAndGetTenantId() {
        Long tenantId = 1001L;
        TenantContext.setTenantId(tenantId);

        assertEquals(tenantId, TenantContext.getTenantId());
        assertTrue(TenantContext.isSet());
    }

    @Test
    @DisplayName("获取租户ID为null时抛出异常")
    void testGetTenantIdThrowsException() {
        assertThrows(IllegalStateException.class, () -> {
            TenantContext.getTenantId();
        });
    }

    @Test
    @DisplayName("获取租户ID或null")
    void testGetTenantIdOrNull() {
        assertNull(TenantContext.getTenantIdOrNull());

        Long tenantId = 1001L;
        TenantContext.setTenantId(tenantId);

        assertEquals(tenantId, TenantContext.getTenantIdOrNull());
    }

    @Test
    @DisplayName("清除租户ID")
    void testClear() {
        Long tenantId = 1001L;
        TenantContext.setTenantId(tenantId);
        assertTrue(TenantContext.isSet());

        TenantContext.clear();
        assertFalse(TenantContext.isSet());
        assertNull(TenantContext.getTenantIdOrNull());
    }

    @Test
    @DisplayName("设置null租户ID不生效")
    void testSetNullTenantId() {
        TenantContext.setTenantId(null);
        assertFalse(TenantContext.isSet());
    }

    @Test
    @DisplayName("多线程隔离测试")
    void testMultiThreadIsolation() throws InterruptedException {
        // 主线程设置租户ID
        TenantContext.setTenantId(1001L);

        // 子线程设置不同的租户ID
        Thread thread = new Thread(() -> {
            TenantContext.setTenantId(2002L);
            assertEquals(2002L, TenantContext.getTenantId());
            TenantContext.clear();
        });
        thread.start();
        thread.join();

        // 主线程的租户ID不受影响
        assertEquals(1001L, TenantContext.getTenantId());
    }
}
