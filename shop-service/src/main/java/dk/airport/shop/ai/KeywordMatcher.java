package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.NodeType;
import dk.airport.shop.domain.Shop;
import dk.airport.shop.domain.ShopCategory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic fallback for {@link AiConciergeService}: matches the words of a question against shop names,
 * descriptions and a small Danish/English vocabulary per category ("kaffe" → FOOD, "parfume" → DUTY_FREE, ...),
 * and spots a destination named in the question ("... på vej til gate B12"). It answers every question the model
 * cannot, so askRoute never fails because Ollama is down.
 *
 * <p>Scoring per shop: +3 for a word from the shop name, +1 for a word from the description, +2 for a category
 * word, +1 when the shop is in the terminal of the destination (or, without one, of the passenger). Shops with the
 * best score above zero are the candidates; the service settles ties by walking distance.
 *
 * <p>The same scoring ranks the language model's answer: the model's need in English words ("painkiller",
 * "currency exchange") is scored together with the question, and the shop the model suggested gets
 * {@link #SUGGESTION_BONUS}. The bonus is deliberately small: it decides between shops that fit the words equally
 * well, and it wins on its own when no word matched at all, but it cannot beat a clear keyword match (a
 * 1.5B model sometimes suggests a juice bar for a headache).
 */
final class KeywordMatcher {

    /**
     * Outcome of a match.
     *
     * @param candidates shops sharing the best score (empty when no word matched anything)
     * @param matchedWords the question words that scored, in question order
     * @param toNode the destination named in the question, or null
     */
    record Match(List<Shop> candidates, List<String> matchedWords, NavNode toNode) {
    }

    /** Points for the shop the language model suggested (see the class comment). */
    static final int SUGGESTION_BONUS = 1;

    /** Words that carry no meaning for the match ("hvor finder jeg en kop ... på vej til gate"). */
    private static final Set<String> STOP_WORDS = Set.of(
            "jeg", "mig", "min", "mit", "mine", "vi", "du", "en", "et", "den", "det", "de", "der", "og", "eller",
            "er", "har", "kan", "skal", "vil", "gerne", "have", "få", "købe", "finde", "finder", "hvor", "hvad",
            "hvilken", "noget", "nogle", "lidt", "kop", "på", "til", "fra", "vej", "vejen", "ved", "nær", "tæt",
            "før", "efter", "inden", "med", "uden", "for", "af", "om", "som", "the", "and", "or", "is", "are",
            "can", "get", "find", "where", "what", "some", "cup", "of", "to", "on", "at", "my", "me", "in",
            "way", "near", "next", "before", "after", "with", "gate", "terminal", "lufthavn", "lufthavnen",
            "airport", "store", "shop", "butik");

    /** Category vocabulary; a stem of five letters or more also matches longer words ("kaffe" → "kaffen"). */
    private static final Map<String, ShopCategory> CATEGORY_WORDS = new HashMap<>();

    static {
        words(ShopCategory.FOOD, "kaffe", "coffee", "latte", "espresso", "cappuccino", "café", "cafe", "te", "tea",
                "mad", "food", "spise", "spiser", "sulten", "hungry", "eat", "frokost", "lunch", "morgenmad",
                "breakfast", "aftensmad", "dinner", "middag", "restaurant", "sandwich", "burger", "pizza", "øl",
                "beer", "vin", "wine", "drikke", "drink", "bar", "bager", "bakery", "brød", "bread", "kanelsnegl",
                "kage", "cake", "snack", "juice", "smørrebrød", "bagværk", "sodavand", "soda", "vand", "water");
        words(ShopCategory.DUTY_FREE, "parfume", "perfume", "spiritus", "whisky", "vodka", "gin", "rom", "rum",
                "chokolade", "chocolate", "tax", "taxfree", "duty", "toldfri", "slik", "candy", "kosmetik",
                "cosmetics", "makeup", "cigaret", "tobak", "tobacco", "alkohol", "alcohol");
        words(ShopCategory.RETAIL, "bog", "bøger", "book", "books", "magasin", "magazine", "avis", "newspaper",
                "tøj", "clothes", "clothing", "jakke", "jacket", "skjorte", "shirt", "legetøj", "toy", "toys", "lego",
                "elektronik", "electronics", "oplader", "charger", "høretelefoner", "headphones", "gave", "gift",
                "souvenir", "tilbehør", "accessories", "sweater", "coat", "reading");
        words(ShopCategory.SERVICE, "apotek", "pharmacy", "medicin", "medicine", "hovedpine", "headache", "plaster",
                "valuta", "currency", "exchange", "veksle", "veksling", "penge", "money", "cash", "kontanter",
                "painkiller", "painkillers", "pain", "dollars", "euros",
                "bagage", "luggage", "baggage", "indpakning", "wrap", "opbevaring", "storage", "kuffert", "suitcase");
        words(ShopCategory.LOUNGE, "lounge", "hvile", "rest", "slappe", "relax", "ro", "quiet", "stille");
    }

    private KeywordMatcher() {
    }

    private static void words(ShopCategory category, String... words) {
        for (String w : words) {
            CATEGORY_WORDS.put(w, category);
        }
    }

    static Match match(String question, List<Shop> shops, List<NavNode> places, NavNode from) {
        return match(question, "", null, shops, places, from);
    }

    /**
     * Scores the question plus extra words, with a bonus for one suggested shop.
     *
     * @param extraWords more words to score with the question (the model's need); the destination is only looked
     *     for in the question itself
     * @param suggestedShop name of the shop the model suggested (case-insensitive), or null
     */
    static Match match(String question, String extraWords, String suggestedShop, List<Shop> shops,
                       List<NavNode> places, NavNode from) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>(tokens(q));
        tokens.addAll(tokens(extraWords == null ? "" : extraWords.toLowerCase(Locale.ROOT)));
        NavNode to = findPlace(q, places);
        String terminal = (to != null ? to : from).getTerminal();

        Map<Shop, Integer> scores = new HashMap<>();
        Set<String> matched = new LinkedHashSet<>();
        for (Shop shop : shops) {
            if (shop.getNode() == null) {
                continue;                                   // cannot be routed to
            }
            int score = 0;
            List<String> name = tokens(shop.getName().toLowerCase(Locale.ROOT));
            List<String> description = shop.getDescription() == null ? List.of()
                    : tokens(shop.getDescription().toLowerCase(Locale.ROOT));
            for (String t : tokens) {
                if (name.stream().anyMatch(n -> sameWord(t, n))) {
                    score += 3;
                    matched.add(t);
                } else if (description.stream().anyMatch(d -> sameWord(t, d))) {
                    score += 1;
                    matched.add(t);
                }
                if (shop.getCategory() == category(t)) {
                    score += 2;
                    matched.add(t);
                }
            }
            if (suggestedShop != null && ConciergePrompt.oneLine(shop.getName()).equalsIgnoreCase(suggestedShop)) {
                score += SUGGESTION_BONUS;
            }
            if (score > 0) {
                if (shop.getTerminal().equalsIgnoreCase(terminal)) {
                    score += 1;
                }
                scores.put(shop, score);
            }
        }
        int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Shop> candidates = scores.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(Shop::getId))
                .toList();
        List<String> matchedInOrder = tokens.stream().filter(matched::contains).distinct().toList();
        return new Match(candidates, matchedInOrder, to);
    }

    /** Lower-cased words of at least two letters/digits that are not stop words. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 2 && !STOP_WORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** The category a question word points at, or null. */
    static ShopCategory category(String token) {
        ShopCategory exact = CATEGORY_WORDS.get(token);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, ShopCategory> e : CATEGORY_WORDS.entrySet()) {
            if (e.getKey().length() >= 5 && token.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** "lego" ~ "lego", "kaffen" ~ "kaffe"; prefixes count from four letters so "te" does not hit "tea" etc. */
    private static boolean sameWord(String a, String b) {
        return a.equals(b)
                || (b.length() >= 4 && a.startsWith(b))
                || (a.length() >= 4 && b.startsWith(a));
    }

    /**
     * The place whose name appears in the question as whole words ("gate b12", "security t2"); for gates the gate
     * code alone ("b12") is enough. The longest name wins, so "Junction Pier B" cannot be beaten by "Pier B".
     */
    static NavNode findPlace(String lowerCaseQuestion, List<NavNode> places) {
        List<NavNode> byLength = new ArrayList<>(places);
        byLength.sort(Comparator.comparingInt((NavNode n) -> n.getName().length()).reversed()
                .thenComparing(NavNode::getId));
        for (NavNode n : byLength) {
            if (containsWord(lowerCaseQuestion, n.getName().toLowerCase(Locale.ROOT))) {
                return n;
            }
        }
        for (NavNode n : byLength) {
            if (n.getType() == NodeType.GATE) {
                String[] parts = n.getName().toLowerCase(Locale.ROOT).split("\\s+");
                String code = parts[parts.length - 1];
                if (code.length() >= 2 && containsWord(lowerCaseQuestion, code)) {
                    return n;
                }
            }
        }
        return null;
    }

    private static boolean containsWord(String haystack, String words) {
        String pattern = Arrays.stream(words.trim().split("\\s+")).map(Pattern::quote)
                .reduce((a, b) -> a + "\\s+" + b).orElse("");
        return !pattern.isEmpty()
                && Pattern.compile("(?<![\\p{L}\\p{N}])" + pattern + "(?![\\p{L}\\p{N}])").matcher(haystack).find();
    }
}
