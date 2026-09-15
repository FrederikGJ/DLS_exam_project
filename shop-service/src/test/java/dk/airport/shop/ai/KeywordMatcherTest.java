package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.NodeType;
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

    static NavNode node(long id, String name, NodeType type) {
        NavNode n = new NavNode(name, "T2", 0, 0, 0, type);
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }
}
