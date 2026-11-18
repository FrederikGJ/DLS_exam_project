package dk.airport.baggage.domain;

/** Error codes exposed to GraphQL clients in errors[].extensions.code */
public enum ErrorCode {
    NOT_FOUND,
    VALIDATION_ERROR,
    INVALID_STATE,
    BAGGAGE_LIMIT_EXCEEDED,
    CONFLICT,
    UNAUTHORIZED,       // no (valid) token, but the operation requires a login
    FORBIDDEN,          // logged in, but the role does not allow the operation
    INTERNAL_ERROR
}
