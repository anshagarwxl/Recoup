package com.recoup.util;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Utility for converting raw timestamps and currency units into clean human-readable simulated timelines. */
public final class TimelineFormatter {

    private TimelineFormatter() {
        // utility class
    }

    /**
     * Converts an action timestamp into a simulated relative timeline string based on initial failure time.
     * e.g., "Day 1, 01:00" or "Day 2, 06:30".
     */
    public static String formatDayOffset(Instant baseTime, Instant eventTime) {
        Objects.requireNonNull(baseTime, "baseTime must not be null");
        Objects.requireNonNull(eventTime, "eventTime must not be null");

        if (eventTime.isBefore(baseTime) || eventTime.equals(baseTime)) {
            return "Day 1, 00:00 (Immediate)";
        }

        Duration duration = Duration.between(baseTime, eventTime);
        long totalSeconds = duration.getSeconds();
        long dayNumber = (totalSeconds / 86_400) + 1;
        long remainingSeconds = totalSeconds % 86_400;
        long hours = remainingSeconds / 3_600;
        long minutes = (remainingSeconds % 3_600) / 60;

        return String.format("Day %d, %02d:%02d", dayNumber, hours, minutes);
    }

    /**
     * Formats paise as Rupee currency with commas and 2 decimals.
     * e.g., 49900 -> "₹499.00", 1500000 -> "₹15,000.00".
     */
    public static String formatRupees(long paise) {
        return String.format("₹%,.2f", paise / 100.0);
    }
}
