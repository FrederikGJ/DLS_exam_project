package dk.airport.baggage.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

/**
 * OpenAPI 3 description of the REST API v1 (dev plan DP-10). springdoc generates it from the controller and the
 * DTOs, so the document cannot drift away from the code:
 *
 * <pre>
 *   http://localhost:8084/v3/api-docs        JSON   (compose; add .yaml for YAML)
 *   http://localhost:8084/swagger-ui.html    Swagger UI - "Authorize" takes a Keycloak access token
 *   http://localhost:8090/swagger-ui/index.html      the same through the Ingress on kind
 * </pre>
 *
 * The committed copy in {@code docs/openapi/baggage-v1.yaml} is exported from the running service.
 *
 * <p>The token is the one the frontend gets from Keycloak, e.g.
 * {@code curl -d grant_type=password -d client_id=airport-frontend -d username=anna -d password=anna
 * http://localhost:8180/realms/airport/protocol/openid-connect/token}; paste the {@code access_token} into
 * Authorize and Swagger UI sends it as {@code Authorization: Bearer ...}.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Airport baggage API",
                version = "v1",
                description = """
                        Baggage of the airport system: register a bag on a confirmed booking, look it up by tag, \
                        list the bags of a booking and move a bag to a new status.

                        The version is part of the path (`/api/baggage/v1`), so a breaking change becomes \
                        `/api/baggage/v2` next to it while v1 keeps working. Errors are RFC 9457 problem details \
                        (`application/problem+json`) with an extra `code` member that uses the same vocabulary as \
                        the GraphQL API's `errors[].extensions.code`.

                        The same operations are available over GraphQL at `/api/baggage/graphql`."""),
        servers = {
            @Server(url = "/", description = "Same origin as the frontend (Kubernetes Ingress)"),
            @Server(url = "http://localhost:8084", description = "docker compose")
        },
        tags = @Tag(name = "Baggage", description = "Bags bound to a booking reference"))
@SecurityScheme(
        name = OpenApiConfig.KEYCLOAK_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Keycloak access token from realm `airport` (roles PASSENGER / OPERATIONS)")
public class OpenApiConfig {

    /** Name of the security scheme, referenced by {@code @SecurityRequirement} on the protected endpoints. */
    public static final String KEYCLOAK_SCHEME = "keycloak";

    /** Media type of every error answer (RFC 9457); used in the {@code @ApiResponse} declarations. */
    public static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
}
