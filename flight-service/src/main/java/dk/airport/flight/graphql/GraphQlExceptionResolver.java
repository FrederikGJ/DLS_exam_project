package dk.airport.flight.graphql;

import dk.airport.flight.domain.ApiException;
import dk.airport.flight.domain.ErrorCode;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps exceptions thrown by data fetchers to GraphQL errors with a machine readable
 * code in {@code errors[].extensions.code}.
 */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final Logger log = LoggerFactory.getLogger(GraphQlExceptionResolver.class);

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ApiException api) {
            return error(env, api.getCode(), api.getMessage());
        }
        if (ex instanceof ConstraintViolationException cve) {
            String msg = cve.getConstraintViolations().stream()
                    .map(v -> lastPathSegment(v) + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            return error(env, ErrorCode.VALIDATION_ERROR, msg);
        }
        if (ex instanceof BindException be) {
            String msg = be.getAllErrors().stream()
                    .map(e -> e.getDefaultMessage() == null ? e.getCode() : e.getDefaultMessage())
                    .collect(Collectors.joining("; "));
            return error(env, ErrorCode.VALIDATION_ERROR, msg);
        }
        if (ex instanceof IllegalArgumentException iae) {
            return error(env, ErrorCode.VALIDATION_ERROR, iae.getMessage());
        }
        if (ex instanceof DataIntegrityViolationException) {
            return error(env, ErrorCode.CONFLICT, "The operation conflicts with existing data");
        }
        log.error("Unhandled error in data fetcher {}", env.getExecutionStepInfo().getPath(), ex);
        return error(env, ErrorCode.INTERNAL_ERROR, "Unexpected error");
    }

    private static String lastPathSegment(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString();
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static GraphQLError error(DataFetchingEnvironment env, ErrorCode code, String message) {
        return GraphqlErrorBuilder.newError(env)
                .message(message)
                .errorType(classify(code))
                .extensions(Map.of("code", code.name()))
                .build();
    }

    private static ErrorType classify(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> ErrorType.NOT_FOUND;
            case INTERNAL_ERROR -> ErrorType.INTERNAL_ERROR;
            default -> ErrorType.BAD_REQUEST;
        };
    }
}
