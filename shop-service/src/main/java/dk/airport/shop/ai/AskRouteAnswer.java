package dk.airport.shop.ai;

import dk.airport.shop.domain.NavNode;
import dk.airport.shop.domain.Route;
import dk.airport.shop.domain.Shop;

/**
 * Result of askRoute (GraphQL type AskRouteAnswer).
 *
 * @param shop           the chosen shop, or null when neither the AI nor the fallback found one
 * @param destination    the destination named in the question, or null
 * @param route          start → shop → destination (start → destination without a shop), null when neither
 * @param aiUsed         true when the model's answer was used, false when the keyword fallback answered
 * @param fallbackReason why the fallback was used (null when aiUsed)
 */
public record AskRouteAnswer(Shop shop, NavNode destination, Route route, boolean aiUsed, String fallbackReason) {}
