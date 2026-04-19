package com.sensex.optiontrader.util;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
public final class DateUtils {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private DateUtils() {}
    public static boolean isMarketOpen() {
        var now = ZonedDateTime.now(IST); var day = now.getDayOfWeek();
        if (day==DayOfWeek.SATURDAY||day==DayOfWeek.SUNDAY) return false;
        var t = now.toLocalTime(); return !t.isBefore(LocalTime.of(9,15))&&!t.isAfter(LocalTime.of(15,30));
    }
    public static LocalDate nextExpiry() {
        var d = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        return d.equals(LocalDate.now())?d.plusWeeks(1):d;
    }
}