package com.company.labor.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageResultTest {

    @Test
    void convertsMapperPageAndRoundsUpTotalPages() {
        Page<String> source = new Page<>(2, 20, 41);
        source.setRecords(List.of("record"));
        PageResult<String> result = PageResult.of(source);
        assertEquals(List.of("record"), result.getRecords());
        assertEquals(41L, result.getTotal());
        assertEquals(2L, result.getCurrent());
        assertEquals(20L, result.getSize());
        assertEquals(3L, result.getPages());
        assertTrue(result.hasNext());
        assertTrue(result.hasPrevious());
        PageResult<String> last = new PageResult<>(List.of("record"), 40L, 2L, 20L);
        assertEquals(2L, last.getPages());
        assertFalse(last.hasNext());
    }

    @Test
    void emptyResultHasNoNavigation() {
        PageResult<String> result = PageResult.empty();
        assertTrue(result.getRecords().isEmpty());
        assertEquals(0L, result.getTotal());
        assertEquals(0L, result.getPages());
        assertEquals(1L, result.getCurrent());
        assertEquals(20L, result.getSize());
        assertFalse(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    @Test
    void missingTotalsOrPageSizeProduceZeroPages() {
        assertEquals(0L, new PageResult<>(List.of(), null, 1L, 20L).getPages());
        assertEquals(0L, new PageResult<>(List.of(), 10L, 1L, null).getPages());
        assertEquals(0L, new PageResult<>(List.of(), 10L, 1L, 0L).getPages());
        PageResult<String> missing = new PageResult<>();
        assertFalse(missing.hasNext());
        assertFalse(missing.hasPrevious());
        missing.setCurrent(1L);
        assertFalse(missing.hasNext());
    }
}
