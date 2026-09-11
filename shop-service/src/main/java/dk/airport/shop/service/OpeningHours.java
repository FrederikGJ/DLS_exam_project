package dk.airport.shop.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets opening hours strings like "06:00-22:00", "22:00-04:00" (overnight) or "24/7".
 * Pure function - the caller supplies the current local time.
 */
public final class OpeningHours {

    public static final String ALWAYS_OPEN = "24/7";
    private static final Pattern RANGE = Pattern.compile("^\\s*(\\d{2}:\\d{2})\\s*-\\s*(\\d{2}:\\d{2})\\s*$");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private OpeningHours() {}

    /** @return true if a shop with the given opening hours is open at {@code now}; false if unparseable. */
    public static boolean isOpen(String openingHours, LocalTime now) {
        if (openingHours == null || now == null) {
            return false;
        }
        if (ALWAYS_OPEN.equals(openingHours.trim())) {
            return true;
        }
        Matcher m = RANGE.matcher(openingHours);
        if (!m.matches()) {
            return false;
        }
        LocalTime open;
        LocalTime close;
        try {
            open = LocalTime.parse(m.group(1), HH_MM);
            close = LocalTime.parse(m.group(2), HH_MM);
        } catch (DateTimeParseException e) {
            return false;
        }
        if (open.equals(close)) {
            return true;                                   // e.g. "00:00-00:00" = whole day
        }
        if (open.isBefore(close)) {                        // same-day range, open inclusive / close exclusive
            return !now.isBefore(open) && now.isBefore(close);
        }
        // overnight range, e.g. 22:00-04:00
        return !now.isBefore(open) || now.isBefore(close);
    }
}
