package dk.airport.booking.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BookingReferenceGeneratorTest {

    private final BookingReferenceGenerator generator = new BookingReferenceGenerator();

    @Test
    void referenceHasSixCharacters() {
        assertThat(generator.generate()).hasSize(6);
    }

    @Test
    void referenceOnlyUsesUnambiguousAlphabet() {
        for (int i = 0; i < 500; i++) {
            String ref = generator.generate();
            assertThat(ref).matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$");
            assertThat(ref).doesNotContain("0", "O", "1", "I");
        }
    }

    @Test
    void referencesAreRandom() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(generator.generate());
        }
        // 32^6 possible values - 1000 draws should practically never collide
        assertThat(seen).hasSizeGreaterThan(990);
    }
}
