package com.company.labor.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdGenerator单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("ID生成器测试")
class IdGeneratorTest {

    @Test
    @DisplayName("雪花算法生成ID")
    void testNextId() {
        Long id1 = IdGenerator.nextId();
        Long id2 = IdGenerator.nextId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        assertTrue(id1 > 0);
        assertTrue(id2 > id1);
    }

    @Test
    @DisplayName("雪花算法并发生成ID")
    void testNextIdConcurrent() throws InterruptedException {
        int threadCount = 10;
        int idCountPerThread = 100;
        java.util.Set<Long> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < idCountPerThread; j++) {
                    ids.add(IdGenerator.nextId());
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // 验证生成的ID是唯一的
        assertEquals(threadCount * idCountPerThread, ids.size());
    }

    @Test
    @DisplayName("生成员工编号")
    void testGenerateEmployeeNo() {
        String no1 = IdGenerator.generateEmployeeNo();
        String no2 = IdGenerator.generateEmployeeNo();

        assertNotNull(no1);
        assertNotNull(no2);
        assertNotEquals(no1, no2);
        assertTrue(no1.startsWith("EMP"));
        assertTrue(no2.startsWith("EMP"));
        assertEquals(14, no1.length()); // EMP + 8位日期 + 3位序列号
    }

    @Test
    @DisplayName("生成工资单号")
    void testGenerateSalaryNo() {
        String no = IdGenerator.generateSalaryNo();

        assertNotNull(no);
        assertTrue(no.startsWith("SAL"));
        assertEquals(14, no.length());
    }

    @Test
    @DisplayName("生成借支单号")
    void testGenerateAdvanceNo() {
        String no = IdGenerator.generateAdvanceNo();

        assertNotNull(no);
        assertTrue(no.startsWith("ADV"));
        assertEquals(14, no.length());
    }

    @Test
    @DisplayName("生成返费单号")
    void testGenerateRebateNo() {
        String no = IdGenerator.generateRebateNo();

        assertNotNull(no);
        assertTrue(no.startsWith("RBT"));
        assertEquals(14, no.length());
    }

    @Test
    @DisplayName("生成项目编号")
    void testGenerateProjectNo() {
        String no = IdGenerator.generateProjectNo();

        assertNotNull(no);
        assertTrue(no.startsWith("PRJ"));
        assertEquals(14, no.length());
    }

    @Test
    @DisplayName("生成渠道编号")
    void testGenerateChannelNo() {
        String no = IdGenerator.generateChannelNo();

        assertNotNull(no);
        assertTrue(no.startsWith("CHN"));
        assertEquals(14, no.length());
    }

    @Test
    @DisplayName("生成自定义业务编号")
    void testGenerateBizNo() {
        String no1 = IdGenerator.generateBizNo("TEST", 5);
        String no2 = IdGenerator.generateBizNo("TEST", 5);

        assertNotNull(no1);
        assertNotNull(no2);
        assertNotEquals(no1, no2);
        assertTrue(no1.startsWith("TEST"));
        assertTrue(no2.startsWith("TEST"));
        assertEquals(17, no1.length()); // TEST + 8位日期 + 5位序列号
    }
}
