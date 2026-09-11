package dk.airport.shop.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningHoursTest {

    @ParameterizedTest
    @CsvSource({
            "06:00-22:00, 06:00, true",    // opening minute is inclusive
            "06:00-22:00, 12:30, true",
            "06:00-22:00, 21:59, true",
            "06:00-22:00, 22:00, false",   // closing minute is exclusive
            "06:00-22:00, 05:59, false",
            "06:00-22:00, 23:15, false",
    })
    void normalRange(String hours, String now, boolean expected) {
        assertThat(OpeningHours.isOpen(hours, LocalTime.parse(now))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "22:00-04:00, 22:00, true",
            "22:00-04:00, 23:59, true",
            "22:00-04:00, 00:30, true",
            "22:00-04:00, 03:59, true",
            "22:00-04:00, 04:00, false",
            "22:00-04:00, 12:00, false",
            "22:00-04:00, 21:59, false",
    })
    void overnightRange(String hours, String now, boolean expected) {
        assertThat(OpeningHours.isOpen(hours, LocalTime.parse(now))).isEqualTo(expected);
    }

    @Test
    void alwaysOpen() {
        assertThat(OpeningHours.isOpen("24/7", LocalTime.MIDNIGHT)).isTrue();
        assertThat(OpeningHours.isOpen(" 24/7 ", LocalTime.NOON)).isTrue();
        assertThat(OpeningHours.isOpen("00:00-00:00", LocalTime.of(13, 37))).isTrue();
    }

    @Test
    void invalidInputIsClosed() {
        assertThat(OpeningHours.isOpen("mandag-fredag", LocalTime.NOON)).isFalse();
        assertThat(OpeningHours.isOpen("25:00-26:00", LocalTime.NOON)).isFalse();
        assertThat(OpeningHours.isOpen("", LocalTime.NOON)).isFalse();
        assertThat(OpeningHours.isOpen(null, LocalTime.NOON)).isFalse();
        assertThat(OpeningHours.isOpen("06:00-22:00", null)).isFalse();
    }
}
