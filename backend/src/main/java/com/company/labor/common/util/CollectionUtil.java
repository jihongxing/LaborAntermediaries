package com.company.labor.common.util;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 集合工具类
 *
 * <p>提供常用的集合操作方法，增强版的Apache Commons Collections。
 *
 * <p>使用示例：
 * <pre>
 * // 空判断（空安全）
 * CollectionUtil.isEmpty(list); // true/false
 * CollectionUtil.isNotEmpty(list); // true/false
 *
 * // 分组
 * Map<Long, List<Employee>> groupByProject =
 *     CollectionUtil.groupBy(employees, Employee::getProjectId);
 *
 * // 映射
 * List<Long> ids = CollectionUtil.map(employees, Employee::getId);
 *
 * // 过滤
 * List<Employee> activeEmployees =
 *     CollectionUtil.filter(employees, e -> e.getStatus() == 1);
 *
 * // 转Map
 * Map<Long, Employee> employeeMap =
 *     CollectionUtil.toMap(employees, Employee::getId);
 *
 * // 去重
 * List<Long> uniqueIds = CollectionUtil.distinct(ids);
 *
 * // 交集、并集、差集
 * List<Long> intersection = CollectionUtil.intersection(list1, list2);
 * List<Long> union = CollectionUtil.union(list1, list2);
 * List<Long> difference = CollectionUtil.difference(list1, list2);
 * </pre>
 *
 * @author 技术团队
 * @since 2026-10-18
 */
public final class CollectionUtil {

    private CollectionUtil() {
        // 私有构造函数，防止实例化
    }

    // ==================== 空判断 ====================

    /**
     * 判断集合是否为空（null或空集合）
     *
     * @param collection 集合
     * @return true为空，false不为空
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * 判断集合是否不为空
     *
     * @param collection 集合
     * @return true不为空，false为空
     */
    public static boolean isNotEmpty(Collection<?> collection) {
        return !isEmpty(collection);
    }

    /**
     * 判断Map是否为空（null或空Map）
     *
     * @param map Map
     * @return true为空，false不为空
     */
    public static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * 判断Map是否不为空
     *
     * @param map Map
     * @return true不为空，false为空
     */
    public static boolean isNotEmpty(Map<?, ?> map) {
        return !isEmpty(map);
    }

    // ==================== 默认值 ====================

    /**
     * 如果集合为空，返回空集合（不可变）
     *
     * @param collection 集合
     * @param <T> 元素类型
     * @return 集合或空集合
     */
    public static <T> List<T> defaultIfEmpty(List<T> collection) {
        return isEmpty(collection) ? Collections.emptyList() : collection;
    }

    /**
     * 如果Map为空，返回空Map（不可变）
     *
     * @param map Map
     * @param <K> 键类型
     * @param <V> 值类型
     * @return Map或空Map
     */
    public static <K, V> Map<K, V> defaultIfEmpty(Map<K, V> map) {
        return isEmpty(map) ? Collections.emptyMap() : map;
    }

    // ==================== 分组 ====================

    /**
     * 分组（按指定字段）
     *
     * @param collection 集合
     * @param keyMapper 键提取函数
     * @param <T> 元素类型
     * @param <K> 键类型
     * @return 分组后的Map
     */
    public static <T, K> Map<K, List<T>> groupBy(Collection<T> collection, Function<T, K> keyMapper) {
        if (isEmpty(collection)) {
            return Collections.emptyMap();
        }
        return collection.stream()
                .collect(Collectors.groupingBy(keyMapper));
    }

    // ==================== 映射 ====================

    /**
     * 映射（提取指定字段）
     *
     * @param collection 集合
     * @param mapper 映射函数
     * @param <T> 源类型
     * @param <R> 目标类型
     * @return 映射后的列表
     */
    public static <T, R> List<R> map(Collection<T> collection, Function<T, R> mapper) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return collection.stream()
                .map(mapper)
                .collect(Collectors.toList());
    }

    // ==================== 过滤 ====================

    /**
     * 过滤（保留满足条件的元素）
     *
     * @param collection 集合
     * @param predicate 过滤条件
     * @param <T> 元素类型
     * @return 过滤后的列表
     */
    public static <T> List<T> filter(Collection<T> collection, java.util.function.Predicate<T> predicate) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return collection.stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    // ==================== 转Map ====================

    /**
     * 转Map（指定键）
     *
     * @param collection 集合
     * @param keyMapper 键提取函数
     * @param <T> 元素类型
     * @param <K> 键类型
     * @return Map
     */
    public static <T, K> Map<K, T> toMap(Collection<T> collection, Function<T, K> keyMapper) {
        if (isEmpty(collection)) {
            return Collections.emptyMap();
        }
        return collection.stream()
                .collect(Collectors.toMap(keyMapper, Function.identity(), (v1, v2) -> v1));
    }

    /**
     * 转Map（指定键和值）
     *
     * @param collection 集合
     * @param keyMapper 键提取函数
     * @param valueMapper 值提取函数
     * @param <T> 元素类型
     * @param <K> 键类型
     * @param <V> 值类型
     * @return Map
     */
    public static <T, K, V> Map<K, V> toMap(Collection<T> collection,
                                             Function<T, K> keyMapper,
                                             Function<T, V> valueMapper) {
        if (isEmpty(collection)) {
            return Collections.emptyMap();
        }
        return collection.stream()
                .collect(Collectors.toMap(keyMapper, valueMapper, (v1, v2) -> v1));
    }

    // ==================== 去重 ====================

    /**
     * 去重（保持原顺序）
     *
     * @param collection 集合
     * @param <T> 元素类型
     * @return 去重后的列表
     */
    public static <T> List<T> distinct(Collection<T> collection) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return collection.stream()
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 去重（按指定字段）
     *
     * @param collection 集合
     * @param keyExtractor 键提取函数
     * @param <T> 元素类型
     * @return 去重后的列表
     */
    public static <T> List<T> distinctBy(Collection<T> collection, Function<T, ?> keyExtractor) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        Set<Object> seen = new HashSet<>();
        return collection.stream()
                .filter(item -> seen.add(keyExtractor.apply(item)))
                .collect(Collectors.toList());
    }

    // ==================== 集合运算 ====================

    /**
     * 交集（返回两个集合的共同元素）
     *
     * @param collection1 集合1
     * @param collection2 集合2
     * @param <T> 元素类型
     * @return 交集
     */
    public static <T> List<T> intersection(Collection<T> collection1, Collection<T> collection2) {
        if (isEmpty(collection1) || isEmpty(collection2)) {
            return Collections.emptyList();
        }
        Set<T> set2 = new HashSet<>(collection2);
        return collection1.stream()
                .filter(set2::contains)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 并集（返回两个集合的所有元素，去重）
     *
     * @param collection1 集合1
     * @param collection2 集合2
     * @param <T> 元素类型
     * @return 并集
     */
    public static <T> List<T> union(Collection<T> collection1, Collection<T> collection2) {
        Set<T> set = new HashSet<>();
        if (isNotEmpty(collection1)) {
            set.addAll(collection1);
        }
        if (isNotEmpty(collection2)) {
            set.addAll(collection2);
        }
        return new ArrayList<>(set);
    }

    /**
     * 差集（返回在集合1中但不在集合2中的元素）
     *
     * @param collection1 集合1
     * @param collection2 集合2
     * @param <T> 元素类型
     * @return 差集
     */
    public static <T> List<T> difference(Collection<T> collection1, Collection<T> collection2) {
        if (isEmpty(collection1)) {
            return Collections.emptyList();
        }
        if (isEmpty(collection2)) {
            return new ArrayList<>(collection1);
        }
        Set<T> set2 = new HashSet<>(collection2);
        return collection1.stream()
                .filter(item -> !set2.contains(item))
                .distinct()
                .collect(Collectors.toList());
    }

    // ==================== 排序 ====================

    /**
     * 排序（自然顺序）
     *
     * @param collection 集合
     * @param <T> 元素类型
     * @return 排序后的列表
     */
    public static <T extends Comparable<T>> List<T> sort(Collection<T> collection) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return collection.stream()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 排序（指定比较器）
     *
     * @param collection 集合
     * @param comparator 比较器
     * @param <T> 元素类型
     * @return 排序后的列表
     */
    public static <T> List<T> sort(Collection<T> collection, Comparator<T> comparator) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return collection.stream()
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    // ==================== 分页 ====================

    /**
     * 分页（内存分页）
     *
     * @param collection 集合
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @param <T> 元素类型
     * @return 分页后的列表
     */
    public static <T> List<T> page(Collection<T> collection, int page, int size) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 10;
        }
        int start = (page - 1) * size;
        int end = Math.min(start + size, collection.size());
        if (start >= collection.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(collection).subList(start, end);
    }

    // ==================== 统计 ====================

    /**
     * 获取集合大小（空安全）
     *
     * @param collection 集合
     * @return 集合大小，如果为null则返回0
     */
    public static int size(Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    /**
     * 获取第一个元素
     *
     * @param collection 集合
     * @param <T> 元素类型
     * @return 第一个元素，如果集合为空则返回null
     */
    public static <T> T first(Collection<T> collection) {
        if (isEmpty(collection)) {
            return null;
        }
        return collection.iterator().next();
    }

    /**
     * 获取最后一个元素
     *
     * @param list 列表
     * @param <T> 元素类型
     * @return 最后一个元素，如果列表为空则返回null
     */
    public static <T> T last(List<T> list) {
        if (isEmpty(list)) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    /**
     * 判断集合是否包含元素
     *
     * @param collection 集合
     * @param element 元素
     * @param <T> 元素类型
     * @return true包含，false不包含
     */
    public static <T> boolean contains(Collection<T> collection, T element) {
        if (isEmpty(collection)) {
            return false;
        }
        return collection.contains(element);
    }

    /**
     * 判断集合是否包含任意一个元素
     *
     * @param collection 集合
     * @param elements 元素列表
     * @param <T> 元素类型
     * @return true包含任意一个，false不包含
     */
    @SafeVarargs
    public static <T> boolean containsAny(Collection<T> collection, T... elements) {
        if (isEmpty(collection) || elements == null || elements.length == 0) {
            return false;
        }
        for (T element : elements) {
            if (collection.contains(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断集合是否包含所有元素
     *
     * @param collection 集合
     * @param elements 元素列表
     * @param <T> 元素类型
     * @return true包含所有，false不包含
     */
    @SafeVarargs
    public static <T> boolean containsAll(Collection<T> collection, T... elements) {
        if (isEmpty(collection) || elements == null || elements.length == 0) {
            return false;
        }
        for (T element : elements) {
            if (!collection.contains(element)) {
                return false;
            }
        }
        return true;
    }
}
