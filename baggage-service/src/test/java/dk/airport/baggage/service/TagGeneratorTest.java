package dk.airport.baggage.service;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TagGeneratorTest {

    @RepeatedTest(20)
    void tagHasExpectedFormat() {
        assertThat(TagGenerator.generate()).matches("^BAG-[A-Z0-9]{8}$");
    }

    @Test
    void tagsAreReasonablyUnique() {
        Set<String> tags = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tags.add(TagGenerator.generate());
        }
        assertThat(tags).hasSize(1000);
    }
}
