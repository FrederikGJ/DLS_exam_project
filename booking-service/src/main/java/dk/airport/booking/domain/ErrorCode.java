package dk.airport.booking.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    INVALID_STATE,
    SEAT_TAKEN,
    CONFLICT,
    UPSTREAM_UNAVAILABLE,
    INTERNAL_ERROR
}
