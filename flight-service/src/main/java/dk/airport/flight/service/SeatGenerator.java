package dk.airport.flight.service;

import dk.airport.flight.domain.SeatClass;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the seat layout for an aircraft: rows of six seats (A-F).
 * The first {@value #BUSINESS_ROWS} rows are BUSINESS, the rest ECONOMY.
 * Same rule as the SQL seed data in V2__seed.sql.
 */
public final class SeatGenerator {

    public static final int SEATS_PER_ROW = 6;
    public static final int BUSINESS_ROWS = 2;
    private static final char[] LETTERS = {'A', 'B', 'C', 'D', 'E', 'F'};

    private SeatGenerator() {}

    public record SeatSpec(String seatNumber, SeatClass seatClass) {}

    public static List<SeatSpec> generate(int totalSeats) {
        if (totalSeats <= 0) {
            throw new IllegalArgumentException("totalSeats must be > 0");
        }
        List<SeatSpec> seats = new ArrayList<>(totalSeats);
        int row = 1;
        while (seats.size() < totalSeats) {
            SeatClass seatClass = row <= BUSINESS_ROWS ? SeatClass.BUSINESS : SeatClass.ECONOMY;
            for (int i = 0; i < SEATS_PER_ROW && seats.size() < totalSeats; i++) {
                seats.add(new SeatSpec(row + String.valueOf(LETTERS[i]), seatClass));
            }
            row++;
        }
        return seats;
    }
}
