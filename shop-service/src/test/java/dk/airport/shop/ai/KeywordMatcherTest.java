package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.NodeType;
import dk.airport.shop.domain.Shop;
import dk.airport.shop.domain.ShopCategory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordMatcherTest {

    static final NavNode SECURITY_T1 = node(1, "Security T1", "T1", NodeType.SECURITY);
    static final NavNode SECURITY_T2 = node(2, "Security T2", "T2", NodeType.SECURITY);
    static final NavNode GATE_B1 = node(3, "Gate B1", "T2", NodeType.GATE);
    static final NavNode GATE_B12 = node(4, "Gate B12", "T2", NodeType.GATE);
    static final List<NavNode> DESTINATIONS = List.of(SECURITY_T1, SECURITY_T2, GATE_B1, GATE_B12);

    static final Shop JOE_T1 = shop(1, "Joe & The Juice", ShopCategory.FOOD, "T1", "Juice, kaffe og sandwiches.");
    static final Shop APOTEKET = shop(2, "Apoteket", ShopCategory.SERVICE, "T1", "Apotek med håndkøbsmedicin.");
    static final Shop STARBUCKS_T2 = shop(3, "Starbucks", ShopCategory.FOOD, "T2", "Kaffe, te og bagværk.");
    static final Shop WHSMITH_T2 = shop(4, "WHSmith", ShopCategory.RETAIL, "T2", "Bøger og magasiner.");
    static final List<Shop> SHOPS = List.of(JOE_T1, APOTEKET, STARBUCKS_T2, WHSMITH_T2);

    @Test
    void danishCoffeeQuestionPicksCoffeeShopInTheDestinationTerminal() {
        KeywordMatcher.Match m = KeywordMatcher.match("Hvor kan jeg købe kaffe på vej til gate B12?", SECURITY_T1,
                SHOPS, DESTINATIONS);
        assertThat(m.shop()).isEqualTo(STARBUCKS_T2);         // same score as Joe, but in the destination's terminal
        assertThat(m.destination()).isEqualTo(GATE_B12);      // "gate b12", not "gate b1"
    }

    @Test
    void englishQuestionAndCategoryWords() {
        assertThat(KeywordMatcher.match("I need a pharmacy", SECURITY_T1, SHOPS, DESTINATIONS).shop()).isEqualTo(APOTEKET);
        assertThat(KeywordMatcher.match("where can I buy books", SECURITY_T2, SHOPS, DESTINATIONS).shop()).isEqualTo(WHSMITH_T2);
    }

    @Test
    void shopNameInTheQuestionWins() {
        // "apotek" starts the name "Apoteket"; "starbucks" is the name itself
        assertThat(KeywordMatcher.match("apotek tak", SECURITY_T2, SHOPS, DESTINATIONS).shop()).isEqualTo(APOTEKET);
        assertThat(KeywordMatcher.match("Starbucks please", SECURITY_T1, SHOPS, DESTINATIONS).shop()).isEqualTo(STARBUCKS_T2);
    }

    @Test
    void noMatchGivesNoShopButStillFindsTheDestination() {
        KeywordMatcher.Match m = KeywordMatcher.match("hvor er toilettet ved gate B12", SECURITY_T2, SHOPS, DESTINATIONS);
        assertThat(m.shop()).isNull();
        assertThat(m.destination()).isEqualTo(GATE_B12);
        assertThat(KeywordMatcher.match("", SECURITY_T2, SHOPS, DESTINATIONS).shop()).isNull();
    }

    static NavNode node(long id, String name, String terminal, NodeType type) {
        NavNode n = new NavNode(name, terminal, 0, 0, 0, type);
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    static Shop shop(long id, String name, ShopCategory category, String terminal, String description) {
        Shop s = new Shop(name, category, terminal, "Zone", 0, "24/7", description,
                node(100 + id, name + " node", terminal, NodeType.SHOP));
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }
}
