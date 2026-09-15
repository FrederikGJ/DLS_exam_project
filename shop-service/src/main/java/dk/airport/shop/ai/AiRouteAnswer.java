package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.Route;
import dk.airport.shop.domain.Shop;

/**
 * Result of {@code askRoute} (GraphQL type AiRouteAnswer).
 *
 * @param interpretation one sentence saying how the question was understood
 * @param shop the shop the question was mapped to; null when nothing matched
 * @param toNode destination named in the question (gate, security, entrance), or null
 * @param route from the passenger's node to the shop, continuing to {@code toNode} when given; null without a shop
 * @param aiUsed true when the language model chose the shop; false when the keyword fallback answered
 * @param fallbackReason why the fallback was used (unreachable, timeout, invalid answer, AI disabled); null if aiUsed
 * @param model the model that answered; null in fallback
 */
public record AiRouteAnswer(String interpretation, Shop shop, NavNode toNode, Route route, boolean aiUsed,
                            String fallbackReason, String model) {
}
