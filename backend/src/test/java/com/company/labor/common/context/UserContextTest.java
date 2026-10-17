package com.company.labor.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserContext单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("用户上下文测试")
class UserContextTest {

    @AfterEach
    void tearDown() {
        // 每个测试后清除用户信息
        UserContext.clear();
    }

    @Test
    @DisplayName("设置和获取用户信息")
    void testSetAndGetUser() {
        UserContext.UserInfo userInfo = new UserContext.UserInfo(1001L, "张三", 2001L, "admin");
        UserContext.setUser(userInfo);

        assertEquals(userInfo, UserContext.getUser());
        assertEquals(1001L, UserContext.getUserId());
        assertEquals("张三", UserContext.getUsername());
        assertEquals(2001L, UserContext.getTenantId());
        assertEquals("admin", UserContext.getRoleCode());
        assertTrue(UserContext.isSet());
        assertTrue(UserContext.isAdmin());
    }

    @Test
    @DisplayName("获取用户信息为null时抛出异常")
    void testGetUserThrowsException() {
        assertThrows(IllegalStateException.class, () -> {
            UserContext.getUser();
        });
    }

    @Test
    @DisplayName("获取用户信息或null")
    void testGetUserOrNull() {
        assertNull(UserContext.getUserOrNull());
        assertNull(UserContext.getUserIdOrNull());
        assertNull(UserContext.getUsernameOrNull());
        assertNull(UserContext.getTenantIdOrNull());
        assertNull(UserContext.getRoleCodeOrNull());

        UserContext.UserInfo userInfo = new UserContext.UserInfo(1001L, "张三", 2001L, "employee");
        UserContext.setUser(userInfo);

        assertEquals(userInfo, UserContext.getUserOrNull());
        assertEquals(1001L, UserContext.getUserIdOrNull());
        assertEquals("张三", UserContext.getUsernameOrNull());
        assertEquals(2001L, UserContext.getTenantIdOrNull());
        assertEquals("employee", UserContext.getRoleCodeOrNull());
        assertFalse(UserContext.isAdmin());
    }

    @Test
    @DisplayName("清除用户信息")
    void testClear() {
        UserContext.UserInfo userInfo = new UserContext.UserInfo(1001L, "张三", 2001L, "admin");
        UserContext.setUser(userInfo);
        assertTrue(UserContext.isSet());

        UserContext.clear();
        assertFalse(UserContext.isSet());
        assertNull(UserContext.getUserOrNull());
    }

    @Test
    @DisplayName("设置null用户信息不生效")
    void testSetNullUser() {
        UserContext.setUser(null);
        assertFalse(UserContext.isSet());
    }

    @Test
    @DisplayName("多线程隔离测试")
    void testMultiThreadIsolation() throws InterruptedException {
        // 主线程设置用户信息
        UserContext.UserInfo mainUserInfo = new UserContext.UserInfo(1001L, "张三", 2001L, "admin");
        UserContext.setUser(mainUserInfo);

        // 子线程设置不同的用户信息
        Thread thread = new Thread(() -> {
            UserContext.UserInfo threadUserInfo = new UserContext.UserInfo(2002L, "李四", 3003L, "employee");
            UserContext.setUser(threadUserInfo);
            assertEquals(2002L, UserContext.getUserId());
            assertEquals("李四", UserContext.getUsername());
            UserContext.clear();
        });
        thread.start();
        thread.join();

        // 主线程的用户信息不受影响
        assertEquals(1001L, UserContext.getUserId());
        assertEquals("张三", UserContext.getUsername());
    }
}
