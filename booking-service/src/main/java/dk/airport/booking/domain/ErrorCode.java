package dk.airport.booking.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    INVALID_STATE,
    SEAT_TAKEN,
    CONFLICT,
    UPSTREAM_UNAVAILABLE,
    UNAUTHORIZED,       // no (valid) token, but the operation requires a login
    FORBIDDEN,          // logged in, but the role does not allow the operation
    INTERNAL_ERROR
}
