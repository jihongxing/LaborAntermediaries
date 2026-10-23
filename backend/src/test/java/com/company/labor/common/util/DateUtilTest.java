package com.company.labor.common.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class DateUtilTest {

    private final LocalDateTime leapDay = LocalDateTime.of(2024, 2, 29, 8, 30, 15);

    @Test
    void formatsSupportedRepresentations() {
        assertEquals("2024-02-29 08:30:15", DateUtil.format(leapDay));
        assertEquals("2024-02-29", DateUtil.format(leapDay.toLocalDate()));
        assertEquals("29/02/2024 08:30", DateUtil.format(leapDay, "dd/MM/yyyy HH:mm"));
        assertEquals("2024-02", DateUtil.formatMonth(leapDay));
        assertEquals("20240229083015", DateUtil.formatCompact(leapDay));
        assertNull(DateUtil.format((LocalDateTime) null));
        assertNull(DateUtil.format((LocalDate) null));
        assertNull(DateUtil.format(null, "yyyy"));
        assertNull(DateUtil.formatMonth(null));
        assertNull(DateUtil.formatCompact(null));
    }

    @Test
    void parsesSupportedFormatsAndRejectsMalformedInput() {
        assertEquals(leapDay, DateUtil.parse("2024-02-29 08:30:15"));
        assertEquals(leapDay.toLocalDate(), DateUtil.parseDate("2024-02-29"));
        assertEquals(leapDay, DateUtil.parse("29/02/2024 08:30:15", "dd/MM/yyyy HH:mm:ss"));
        assertNull(DateUtil.parse(null));
        assertNull(DateUtil.parse(""));
        assertNull(DateUtil.parseDate(null));
        assertNull(DateUtil.parseDate(""));
        assertNull(DateUtil.parse(null, "yyyy"));
        assertNull(DateUtil.parse("", "yyyy"));
        assertThrows(DateTimeParseException.class, () -> DateUtil.parse("invalid"));
        assertThrows(DateTimeParseException.class, () -> DateUtil.parseDate("2024-13-01"));
    }

    @Test
    void convertsUsingShanghaiZoneRegardlessOfHostZone() {
        LocalDateTime epochInShanghai = LocalDateTime.of(1970, 1, 1, 8, 0);
        assertEquals(Date.from(Instant.EPOCH), DateUtil.toDate(epochInShanghai));
        assertEquals(epochInShanghai, DateUtil.toLocalDateTime(new Date(0)));
        assertEquals(epochInShanghai, DateUtil.toLocalDateTime(0L));
        assertEquals(0L, DateUtil.toTimestamp(epochInShanghai));
        assertNull(DateUtil.toDate(null));
        assertNull(DateUtil.toLocalDateTime((Date) null));
        assertNull(DateUtil.toLocalDateTime((Long) null));
        assertNull(DateUtil.toTimestamp(null));
    }

    @Test
    void calculatesSignedWholeUnitsAcrossLeapDay() {
        LocalDate start = LocalDate.of(2024, 2, 28);
        LocalDate end = LocalDate.of(2024, 3, 1);
        assertEquals(2, DateUtil.daysBetween(start, end));
        assertEquals(-2, DateUtil.daysBetween(end, start));
        assertEquals(2, DateUtil.daysBetween(start.atStartOfDay(), end.atStartOfDay()));
        assertEquals(47, DateUtil.hoursBetween(start.atStartOfDay(), end.atStartOfDay().minusMinutes(1)));
        assertEquals(2879, DateUtil.minutesBetween(start.atStartOfDay(), end.atStartOfDay().minusSeconds(1)));
        assertEquals(0, DateUtil.daysBetween((LocalDate) null, end));
        assertEquals(0, DateUtil.daysBetween(start, (LocalDate) null));
        assertEquals(0, DateUtil.daysBetween(null, leapDay));
        assertEquals(0, DateUtil.daysBetween(leapDay, null));
        assertEquals(0, DateUtil.hoursBetween(null, leapDay));
        assertEquals(0, DateUtil.hoursBetween(leapDay, null));
        assertEquals(0, DateUtil.minutesBetween(null, leapDay));
        assertEquals(0, DateUtil.minutesBetween(leapDay, null));
    }

    @Test
    void includesBothRangeBoundariesAndRejectsMissingOrReversedBounds() {
        LocalDate start = leapDay.toLocalDate();
        LocalDate end = start.plusDays(1);
        assertTrue(DateUtil.isBetween(start, start, end));
        assertTrue(DateUtil.isBetween(end, start, end));
        assertFalse(DateUtil.isBetween(start.minusDays(1), start, end));
        assertFalse(DateUtil.isBetween(end.plusDays(1), start, end));
        assertFalse(DateUtil.isBetween(start, end, start));
        assertFalse(DateUtil.isBetween(null, start, end));
        assertFalse(DateUtil.isBetween(start, null, end));
        assertFalse(DateUtil.isBetween(start, start, null));
        LocalDateTime finish = leapDay.plusHours(1);
        assertTrue(DateUtil.isBetween(leapDay, leapDay, finish));
        assertTrue(DateUtil.isBetween(finish, leapDay, finish));
        assertFalse(DateUtil.isBetween(leapDay.minusSeconds(1), leapDay, finish));
        assertFalse(DateUtil.isBetween(finish.plusSeconds(1), leapDay, finish));
        assertFalse(DateUtil.isBetween(null, leapDay, finish));
        assertFalse(DateUtil.isBetween(leapDay, null, finish));
        assertFalse(DateUtil.isBetween(leapDay, leapDay, null));
    }

    @Test
    void currentTimeFallsWithinObservationWindow() {
        LocalDateTime before = LocalDateTime.now();
        LocalDate todayBefore = LocalDate.now();
        long timestampBefore = System.currentTimeMillis();
        LocalDateTime actual = DateUtil.now();
        LocalDate today = DateUtil.today();
        long timestamp = DateUtil.currentTimestamp();
        assertFalse(actual.isBefore(before));
        assertFalse(actual.isAfter(LocalDateTime.now()));
        assertFalse(today.isBefore(todayBefore));
        assertFalse(today.isAfter(LocalDate.now()));
        assertTrue(timestamp >= timestampBefore);
        assertTrue(timestamp <= System.currentTimeMillis());
    }
}
