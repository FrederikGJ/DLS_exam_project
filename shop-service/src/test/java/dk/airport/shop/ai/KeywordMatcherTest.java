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

    @Test
    void tokensDropStopWordsAndPunctuation() {
        assertThat(KeywordMatcher.tokens("hvor finder jeg en kop kaffe på vej til gate b12?"))
                .containsExactly("kaffe", "b12");
        assertThat(KeywordMatcher.tokens("where can i get some coffee before security t2"))
                .containsExactly("coffee", "security", "t2");
    }

    @Test
    void categoryWordsMatchExactlyOrByStem() {
        assertThat(KeywordMatcher.category("kaffe")).isEqualTo(ShopCategory.FOOD);
        assertThat(KeywordMatcher.category("kaffen")).isEqualTo(ShopCategory.FOOD);          // stem "kaffe"
        assertThat(KeywordMatcher.category("te")).isEqualTo(ShopCategory.FOOD);
        assertThat(KeywordMatcher.category("restaurant")).isEqualTo(ShopCategory.FOOD);
        assertThat(KeywordMatcher.category("rest")).isEqualTo(ShopCategory.LOUNGE);          // short words: exact only
        assertThat(KeywordMatcher.category("parfumen")).isEqualTo(ShopCategory.DUTY_FREE);
        assertThat(KeywordMatcher.category("apoteket")).isEqualTo(ShopCategory.SERVICE);
        assertThat(KeywordMatcher.category("lego")).isEqualTo(ShopCategory.RETAIL);
        assertThat(KeywordMatcher.category("b12")).isNull();
    }

    @Test
    void placeIsFoundByFullNameOrGateCode() {
        NavNode securityT1 = node(3L, "Security T1", NodeType.SECURITY);
        NavNode securityT2 = node(24L, "Security T2", NodeType.SECURITY);
        NavNode gateB12 = node(31L, "Gate B12", NodeType.GATE);
        NavNode gateB15 = node(32L, "Gate B15", NodeType.GATE);
        List<NavNode> places = List.of(securityT1, securityT2, gateB12, gateB15);

        assertThat(KeywordMatcher.findPlace("kaffe på vej til gate b12", places)).isSameAs(gateB12);
        assertThat(KeywordMatcher.findPlace("jeg skal til b15 om lidt", places)).isSameAs(gateB15);
        assertThat(KeywordMatcher.findPlace("noget mad før security t2", places)).isSameAs(securityT2);
        assertThat(KeywordMatcher.findPlace("noget mad før security", places)).isNull();     // ambiguous
        assertThat(KeywordMatcher.findPlace("vitamin b123", places)).isNull();               // not a whole word
    }

    @Test
    void extraWordsAndSuggestedShopAreScoredWithTheQuestion() {
        NavNode here = node(24L, "Security T2", NodeType.SECURITY);
        Shop pharmacy = shop(5L, "Apoteket", ShopCategory.SERVICE, "T1", "Apotek med håndkøbsmedicin.");
        Shop forex = shop(13L, "Forex Valutaveksling", ShopCategory.SERVICE, "T2", "Valutaveksling og kontanter.");
        Shop books = shop(14L, "WHSmith", ShopCategory.RETAIL, "T2", "Bøger, magasiner og elektronik.");
        List<Shop> shops = List.of(pharmacy, forex, books);

        // the question alone has no known word; the model's "painkiller" points at SERVICE, and T2 favours Forex ...
        assertThat(KeywordMatcher.match("noget mod køresyge", shops, List.of(), here).candidates()).isEmpty();
        assertThat(KeywordMatcher.match("noget mod køresyge", "painkiller", null, shops, List.of(), here)
                .candidates()).containsExactly(forex);
        // ... until the suggestion settles the tie between the two SERVICE shops
        KeywordMatcher.Match m = KeywordMatcher.match("noget mod køresyge", "painkiller", "apoteket", shops,
                List.of(), here);
        assertThat(m.candidates()).containsExactly(pharmacy, forex);          // sorted by id
        assertThat(m.matchedWords()).containsExactly("painkiller");
        // a suggestion alone is enough when no word matches, but loses to a clear match in the question
        assertThat(KeywordMatcher.match("xyzzy", "", "WHSmith", shops, List.of(), here).candidates())
                .containsExactly(books);
        assertThat(KeywordMatcher.match("veksle penge", "currency exchange", "WHSmith", shops, List.of(), here)
                .candidates()).containsExactly(forex);
    }

    static Shop shop(long id, String name, ShopCategory category, String terminal, String description) {
        Shop s = new Shop(name, category, terminal, "Airside", 0, "06:00-22:00", description,
                node(100 + id, name, NodeType.SHOP));
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    static NavNode node(long id, String name, NodeType type) {
        NavNode n = new NavNode(name, "T2", 0, 0, 0, type);
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }
}
