package dk.airport.flight.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    INVALID_STATE,
    SEAT_TAKEN,
    CONFLICT,
    INTERNAL_ERROR
}
