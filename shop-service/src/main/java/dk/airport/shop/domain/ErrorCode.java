package dk.airport.shop.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    ROUTE_NOT_FOUND,
    CONFLICT,
    INTERNAL_ERROR
}
