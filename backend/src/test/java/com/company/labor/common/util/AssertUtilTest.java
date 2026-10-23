package com.company.labor.common.util;

import com.company.labor.common.constant.ErrorCode;
import com.company.labor.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssertUtilTest {

    @Test
    void nullAssertionsPreserveErrorCodeAndMessage() {
        AssertUtil.notNull("present", "required");
        AssertUtil.notNull("present", ErrorCode.NOT_FOUND);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> AssertUtil.notNull(null, "required"));
        assertEquals(ErrorCode.BAD_REQUEST, missing.getCode());
        assertEquals("required", missing.getMessage());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class,
                () -> AssertUtil.notNull(null, ErrorCode.NOT_FOUND)).getCode());
    }

    @Test
    void emptyAssertionsAcceptValuesAndRejectNullEmptyOrBlank() {
        AssertUtil.notEmpty("value", "required");
        AssertUtil.notEmpty(List.of("value"), "required");
        AssertUtil.notEmpty(Map.of("key", "value"), "required");
        AssertUtil.notEmpty(new Object[]{"value"}, "required");
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty((String) null, "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty("  ", "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty((Collection<?>) null, "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty(List.of(), "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty((Map<?, ?>) null, "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty(Map.of(), "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty((Object[]) null, "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEmpty(new Object[0], "required"));
    }

    @Test
    void booleanAssertionsEnforceBothPolarities() {
        AssertUtil.isTrue(true, "required");
        AssertUtil.isTrue(true, ErrorCode.BAD_REQUEST);
        AssertUtil.isFalse(false, "required");
        AssertUtil.isFalse(false, ErrorCode.BAD_REQUEST);
        assertThrows(BusinessException.class, () -> AssertUtil.isTrue(false, "required"));
        assertThrows(BusinessException.class, () -> AssertUtil.isFalse(true, "required"));
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> AssertUtil.isTrue(false, ErrorCode.FORBIDDEN)).getCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> AssertUtil.isFalse(true, ErrorCode.FORBIDDEN)).getCode());
    }

    @Test
    void equalityAssertionsHandleNullAndValues() {
        AssertUtil.equals(null, null, "equal");
        AssertUtil.equals("same", "same", "equal");
        AssertUtil.notEquals(null, "value", "different");
        AssertUtil.notEquals("value", null, "different");
        AssertUtil.notEquals("first", "second", "different");
        assertThrows(BusinessException.class, () -> AssertUtil.equals(null, "value", "equal"));
        assertThrows(BusinessException.class, () -> AssertUtil.equals("value", null, "equal"));
        assertThrows(BusinessException.class, () -> AssertUtil.equals("first", "second", "equal"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEquals("same", "same", "different"));
        assertThrows(BusinessException.class, () -> AssertUtil.notEquals(null, null, "different"));
    }

    @Test
    void numericAndLengthRangesIncludeBoundaries() {
        AssertUtil.inRange(1, 1, 2, "range");
        AssertUtil.inRange(2, 1, 2, "range");
        AssertUtil.inRange(1L, 1L, 2L, "range");
        AssertUtil.inRange(2L, 1L, 2L, "range");
        AssertUtil.lengthInRange("a", 1, 2, "range");
        AssertUtil.lengthInRange("ab", 1, 2, "range");
        assertThrows(BusinessException.class, () -> AssertUtil.inRange(0, 1, 2, "range"));
        assertThrows(BusinessException.class, () -> AssertUtil.inRange(3, 1, 2, "range"));
        assertThrows(BusinessException.class, () -> AssertUtil.inRange(0L, 1L, 2L, "range"));
        assertThrows(BusinessException.class, () -> AssertUtil.inRange(3L, 1L, 2L, "range"));
        assertThrows(BusinessException.class, () -> AssertUtil.lengthInRange(null, 1, 2, "range"));
        assertThrows(BusinessException.class, () -> AssertUtil.lengthInRange("", 1, 2, "range"));
        assertThrows(BusinessException.class, () -> AssertUtil.lengthInRange("abc", 1, 2, "range"));
    }

    @Test
    void authorizationAssertionsPreserveHttpErrorSemantics() {
        AssertUtil.hasPermission(true, "denied");
        AssertUtil.isLogin(true);
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> AssertUtil.hasPermission(false, "denied")).getCode());
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> AssertUtil.isLogin(false)).getCode());
    }

    @Test
    void failAlwaysThrowsWithProvidedError() {
        assertEquals("failure", assertThrows(BusinessException.class,
                () -> AssertUtil.fail("failure")).getMessage());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> AssertUtil.fail(ErrorCode.FORBIDDEN)).getCode());
        BusinessException failure = assertThrows(BusinessException.class,
                () -> AssertUtil.fail(ErrorCode.FORBIDDEN, "conflict"));
        assertEquals(ErrorCode.FORBIDDEN, failure.getCode());
        assertEquals("conflict", failure.getMessage());
    }
}
