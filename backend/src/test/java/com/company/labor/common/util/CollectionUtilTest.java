package com.company.labor.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CollectionUtil单元测试
 *
 * @author 技术团队
 * @since 2026-10-18
 */
@DisplayName("集合工具测试")
class CollectionUtilTest {

    @Test
    @DisplayName("空判断")
    void testIsEmpty() {
        assertTrue(CollectionUtil.isEmpty((List<String>) null));
        assertTrue(CollectionUtil.isEmpty(List.of()));
        assertFalse(CollectionUtil.isEmpty(List.of("a")));

        assertTrue(CollectionUtil.isEmpty((Map<String, String>) null));
        assertTrue(CollectionUtil.isEmpty(Map.of()));
        assertFalse(CollectionUtil.isEmpty(Map.of("a", "b")));

        assertFalse(CollectionUtil.isNotEmpty((List<String>) null));
        assertFalse(CollectionUtil.isNotEmpty(List.of()));
        assertTrue(CollectionUtil.isNotEmpty(List.of("a")));
    }

    @Test
    @DisplayName("默认值")
    void testDefaultIfEmpty() {
        assertEquals(Collections.emptyList(), CollectionUtil.defaultIfEmpty((List<String>) null));
        assertEquals(Collections.emptyList(), CollectionUtil.defaultIfEmpty(List.of()));
        assertEquals(List.of("a"), CollectionUtil.defaultIfEmpty(List.of("a")));

        assertEquals(Collections.emptyMap(), CollectionUtil.defaultIfEmpty((Map<String, String>) null));
        assertEquals(Collections.emptyMap(), CollectionUtil.defaultIfEmpty(Map.of()));
    }

    @Test
    @DisplayName("分组")
    void testGroupBy() {
        List<Employee> employees = Arrays.asList(
                new Employee(1L, "张三", 100L),
                new Employee(2L, "李四", 100L),
                new Employee(3L, "王五", 200L)
        );

        Map<Long, List<Employee>> groupByProject = CollectionUtil.groupBy(employees, Employee::getProjectId);

        assertEquals(2, groupByProject.size());
        assertEquals(2, groupByProject.get(100L).size());
        assertEquals(1, groupByProject.get(200L).size());

        assertEquals(Collections.emptyMap(), CollectionUtil.groupBy(null, Employee::getProjectId));
    }

    @Test
    @DisplayName("映射")
    void testMap() {
        List<Employee> employees = Arrays.asList(
                new Employee(1L, "张三", 100L),
                new Employee(2L, "李四", 100L),
                new Employee(3L, "王五", 200L)
        );

        List<Long> ids = CollectionUtil.map(employees, Employee::getId);

        assertEquals(Arrays.asList(1L, 2L, 3L), ids);

        assertEquals(Collections.emptyList(), CollectionUtil.map(null, Employee::getId));
    }

    @Test
    @DisplayName("过滤")
    void testFilter() {
        List<Employee> employees = Arrays.asList(
                new Employee(1L, "张三", 100L),
                new Employee(2L, "李四", 100L),
                new Employee(3L, "王五", 200L)
        );

        List<Employee> filtered = CollectionUtil.filter(employees, e -> e.getProjectId() == 100L);

        assertEquals(2, filtered.size());
        assertEquals("张三", filtered.get(0).getName());
        assertEquals("李四", filtered.get(1).getName());

        assertEquals(Collections.emptyList(), CollectionUtil.filter(null, e -> true));
    }

    @Test
    @DisplayName("转Map")
    void testToMap() {
        List<Employee> employees = Arrays.asList(
                new Employee(1L, "张三", 100L),
                new Employee(2L, "李四", 100L),
                new Employee(3L, "王五", 200L)
        );

        Map<Long, Employee> employeeMap = CollectionUtil.toMap(employees, Employee::getId);

        assertEquals(3, employeeMap.size());
        assertEquals("张三", employeeMap.get(1L).getName());

        Map<Long, String> nameMap = CollectionUtil.toMap(employees, Employee::getId, Employee::getName);

        assertEquals(3, nameMap.size());
        assertEquals("张三", nameMap.get(1L));

        assertEquals(Collections.emptyMap(), CollectionUtil.toMap(null, Employee::getId));
    }

    @Test
    @DisplayName("去重")
    void testDistinct() {
        List<Long> ids = Arrays.asList(1L, 2L, 2L, 3L, 3L, 3L);
        List<Long> distinctIds = CollectionUtil.distinct(ids);

        assertEquals(Arrays.asList(1L, 2L, 3L), distinctIds);

        assertEquals(Collections.emptyList(), CollectionUtil.distinct(null));
    }

    @Test
    @DisplayName("按字段去重")
    void testDistinctBy() {
        List<Employee> employees = Arrays.asList(
                new Employee(1L, "张三", 100L),
                new Employee(2L, "李四", 100L),
                new Employee(3L, "王五", 200L)
        );

        List<Employee> distinctEmployees = CollectionUtil.distinctBy(employees, Employee::getProjectId);

        assertEquals(2, distinctEmployees.size());
        assertEquals("张三", distinctEmployees.get(0).getName());
        assertEquals("王五", distinctEmployees.get(1).getName());
    }

    @Test
    @DisplayName("交集")
    void testIntersection() {
        List<Long> list1 = Arrays.asList(1L, 2L, 3L);
        List<Long> list2 = Arrays.asList(2L, 3L, 4L);

        List<Long> intersection = CollectionUtil.intersection(list1, list2);

        assertEquals(Arrays.asList(2L, 3L), intersection);

        assertEquals(Collections.emptyList(), CollectionUtil.intersection(null, list2));
        assertEquals(Collections.emptyList(), CollectionUtil.intersection(list1, null));
    }

    @Test
    @DisplayName("并集")
    void testUnion() {
        List<Long> list1 = Arrays.asList(1L, 2L, 3L);
        List<Long> list2 = Arrays.asList(2L, 3L, 4L);

        List<Long> union = CollectionUtil.union(list1, list2);

        assertEquals(4, union.size());
        assertTrue(union.containsAll(Arrays.asList(1L, 2L, 3L, 4L)));
    }

    @Test
    @DisplayName("差集")
    void testDifference() {
        List<Long> list1 = Arrays.asList(1L, 2L, 3L);
        List<Long> list2 = Arrays.asList(2L, 3L, 4L);

        List<Long> difference = CollectionUtil.difference(list1, list2);

        assertEquals(List.of(1L), difference);

        assertEquals(Collections.emptyList(), CollectionUtil.difference(null, list2));
        assertEquals(list1, CollectionUtil.difference(list1, null));
    }

    @Test
    @DisplayName("排序")
    void testSort() {
        List<Integer> list = Arrays.asList(3, 1, 2);
        List<Integer> sorted = CollectionUtil.sort(list);

        assertEquals(Arrays.asList(1, 2, 3), sorted);

        List<Integer> sortedDesc = CollectionUtil.sort(list, Comparator.reverseOrder());

        assertEquals(Arrays.asList(3, 2, 1), sortedDesc);

        assertEquals(Collections.emptyList(), CollectionUtil.sort(null));
    }

    @Test
    @DisplayName("分页")
    void testPage() {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        List<Integer> page1 = CollectionUtil.page(list, 1, 3);
        assertEquals(Arrays.asList(1, 2, 3), page1);

        List<Integer> page2 = CollectionUtil.page(list, 2, 3);
        assertEquals(Arrays.asList(4, 5, 6), page2);

        List<Integer> page5 = CollectionUtil.page(list, 5, 3);
        assertEquals(Collections.emptyList(), page5);

        assertEquals(Collections.emptyList(), CollectionUtil.page(null, 1, 3));
    }

    @Test
    @DisplayName("统计")
    void testStatistics() {
        List<String> list = Arrays.asList("a", "b", "c");

        assertEquals(3, CollectionUtil.size(list));
        assertEquals(0, CollectionUtil.size(null));

        assertEquals("a", CollectionUtil.first(list));
        assertNull(CollectionUtil.first(null));

        assertEquals("c", CollectionUtil.last(list));
        assertNull(CollectionUtil.last(null));

        assertTrue(CollectionUtil.contains(list, "a"));
        assertFalse(CollectionUtil.contains(list, "d"));
        assertFalse(CollectionUtil.contains(null, "a"));

        assertTrue(CollectionUtil.containsAny(list, "a", "d"));
        assertFalse(CollectionUtil.containsAny(list, "d", "e"));

        assertTrue(CollectionUtil.containsAll(list, "a", "b"));
        assertFalse(CollectionUtil.containsAll(list, "a", "d"));
    }

    // 测试用的Employee类
    static class Employee {
        private Long id;
        private String name;
        private Long projectId;

        public Employee(Long id, String name, Long projectId) {
            this.id = id;
            this.name = name;
            this.projectId = projectId;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Long getProjectId() {
            return projectId;
        }
    }
}
