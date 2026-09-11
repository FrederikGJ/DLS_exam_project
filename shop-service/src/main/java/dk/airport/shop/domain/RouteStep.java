package dk.airport.shop.domain;

/** One step of a route: the node reached, a human readable instruction and the distance from the previous step. */
public record RouteStep(NavNode node, String instruction, int distance) {}
