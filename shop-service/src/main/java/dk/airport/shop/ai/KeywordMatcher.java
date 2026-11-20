package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.Shop;
import dk.airport.shop.domain.ShopCategory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * askRoute without AI: plain keyword matching on the question (Danish and English). Used when Ollama is disabled,
 * down, too slow or answers with something invalid, so askRoute always gives an answer.
 *
 * <p>Score per shop: +3 when a word from the question starts the shop's name ("starbucks", "apotek" → Apoteket),
 * +2 when a word maps to the shop's category ("kaffe" → FOOD), +1 per word found in the description ("kaffe" in
 * "Juice, kaffe og sandwiches"), +1 when the shop is in the terminal the passenger is heading for. Highest score wins,
 * ties go to the lowest id (deterministic). The destination is a gate/security/entrance whose name is in the question.
 */
public final class KeywordMatcher {

    /** Word in the question → shop category. */
    static final Map<String, ShopCategory> CATEGORY_WORDS = Stream.of(
                    category(ShopCategory.FOOD, "kaffe", "coffee", "cafe", "café", "te", "tea", "mad", "food", "spise",
                            "eat", "sulten", "hungry", "frokost", "lunch", "morgenmad", "breakfast", "aftensmad",
                            "dinner", "restaurant", "sandwich", "juice", "øl", "beer", "drikke", "drink", "bar",
                            "bager", "bageri", "bakery", "kage", "cake", "snack", "snacks"),
                    category(ShopCategory.DUTY_FREE, "parfume", "perfume", "spiritus", "whisky", "alkohol", "alcohol",
                            "chokolade", "chocolate", "slik", "candy", "kosmetik", "cosmetics", "makeup", "tax",
                            "taxfree", "duty", "toldfri"),
                    category(ShopCategory.RETAIL, "bog", "bøger", "book", "books", "magasin", "magazine", "avis",
                            "newspaper", "tøj", "clothes", "legetøj", "toys", "lego", "elektronik", "electronics",
                            "oplader", "charger", "gave", "gift", "souvenir"),
                    category(ShopCategory.SERVICE, "apotek", "pharmacy", "medicin", "medicine", "hovedpine",
                            "headache", "valuta", "currency", "veksle", "exchange", "kontanter", "cash", "penge",
                            "money", "bagage", "luggage", "baggage", "indpakning", "wrap", "opbevaring", "storage"),
                    category(ShopCategory.LOUNGE, "lounge", "hvile", "rest", "slappe", "relax"))
            .flatMap(m -> m.entrySet().stream())
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    /** Words shorter than this are only used for the category lookup ("te", "øl"), not for name/description. */
    private static final int MIN_WORD = 4;

    public record Match(Shop shop, NavNode destination) {}

    private KeywordMatcher() {
    }

    public static Match match(String question, NavNode from, Collection<Shop> shops, Collection<NavNode> destinations) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<String> words = words(q);
        NavNode destination = findDestination(q, destinations);
        String terminal = (destination != null ? destination : from).getTerminal();

        Shop best = null;
        int bestScore = 0;
        for (Shop shop : shops.stream().sorted(Comparator.comparing(Shop::getId)).toList()) {
            int score = score(words, shop);
            if (score > 0 && shop.getTerminal().equalsIgnoreCase(terminal)) {
                score += 1;
            }
            if (score > bestScore) {                     // strictly greater: ties keep the lowest id
                best = shop;
                bestScore = score;
            }
        }
        return new Match(best, destination);
    }

    static int score(List<String> words, Shop shop) {
        List<String> name = words(shop.getName().toLowerCase(Locale.ROOT));
        List<String> description = words(shop.getDescription() == null ? "" : shop.getDescription().toLowerCase(Locale.ROOT));
        int score = 0;
        for (String w : words) {
            if (w.length() >= MIN_WORD && name.stream().anyMatch(n -> n.startsWith(w))) {
                score += 3;
            }
            if (CATEGORY_WORDS.get(w) == shop.getCategory()) {
                score += 2;
            }
            if (w.length() >= MIN_WORD && description.contains(w)) {
                score += 1;
            }
        }
        return score;
    }

    /** The destination whose full name occurs in the question as whole words ("gate b12", not "gate b1"). */
    static NavNode findDestination(String lowerCaseQuestion, Collection<NavNode> destinations) {
        return destinations.stream()
                .filter(n -> Pattern.compile("\\b" + Pattern.quote(n.getName().toLowerCase(Locale.ROOT)) + "\\b",
                        Pattern.UNICODE_CHARACTER_CLASS).matcher(lowerCaseQuestion).find())
                .max(Comparator.comparingInt(n -> n.getName().length()))     // the most specific name
                .orElse(null);
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+")).filter(w -> !w.isEmpty()).toList();
    }

    private static Map<String, ShopCategory> category(ShopCategory category, String... words) {
        return Arrays.stream(words).collect(Collectors.toMap(w -> w, w -> category));
    }
}
