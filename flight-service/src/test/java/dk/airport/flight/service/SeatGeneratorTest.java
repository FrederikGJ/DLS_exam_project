package dk.airport.flight.service;

import dk.airport.flight.domain.SeatClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatGeneratorTest {

    @Test
    void generatesExactNumberOfUniqueSeats() {
        List<SeatGenerator.SeatSpec> seats = SeatGenerator.generate(180);
        assertThat(seats).hasSize(180);
        assertThat(seats.stream().map(SeatGenerator.SeatSpec::seatNumber).distinct()).hasSize(180);
        assertThat(seats.get(0).seatNumber()).isEqualTo("1A");
        assertThat(seats.get(179).seatNumber()).isEqualTo("30F");
    }

    @Test
    void firstTwoRowsAreBusinessRestEconomy() {
        List<SeatGenerator.SeatSpec> seats = SeatGenerator.generate(30);
        assertThat(seats.subList(0, 12)).allMatch(s -> s.seatClass() == SeatClass.BUSINESS);
        assertThat(seats.subList(12, 30)).allMatch(s -> s.seatClass() == SeatClass.ECONOMY);
    }

    @Test
    void handlesSeatCountsThatAreNotMultiplesOfSix() {
        List<SeatGenerator.SeatSpec> seats = SeatGenerator.generate(7);
        assertThat(seats).extracting(SeatGenerator.SeatSpec::seatNumber)
                .containsExactly("1A", "1B", "1C", "1D", "1E", "1F", "2A");
    }

    @Test
    void rejectsNonPositiveSeatCount() {
        assertThatThrownBy(() -> SeatGenerator.generate(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
