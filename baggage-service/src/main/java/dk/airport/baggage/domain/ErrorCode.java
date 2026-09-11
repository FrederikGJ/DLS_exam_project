package dk.airport.baggage.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    INVALID_STATE,
    BAGGAGE_LIMIT_EXCEEDED,
    CONFLICT,
    INTERNAL_ERROR
}
