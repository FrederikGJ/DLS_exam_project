package dk.airport.baggage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * OAuth2 resource-server security for the GraphQL API (dev plan DP-02) and the REST API v1 (DP-09).
 *
 * <p>Every request may carry a Keycloak JWT as {@code Authorization: Bearer ...}. The token is validated with the
 * signing keys from {@code JWK_SET_URI} and its {@code iss} claim must equal {@code OIDC_ISSUER_URI}
 * (see application.yml). Realm roles in {@code realm_access.roles} become {@code ROLE_<name>} authorities
 * ({@link KeycloakRoleConverter}), so controller methods can be guarded with {@code @PreAuthorize("hasRole(...)")}.
 *
 * <p>At the HTTP level the GraphQL endpoint is open: a single endpoint serves public queries and protected
 * mutations, so the decision is taken per operation in the controller (method security), not per URL. A request
 * without a token is anonymous there and a guarded operation then fails with {@code UNAUTHORIZED}; a token with
 * the wrong role gives {@code FORBIDDEN} (mapped in GraphQlExceptionResolver). An invalid or expired token is
 * rejected with HTTP 401 before the request reaches GraphQL. Health probes and GraphiQL (dev only) are public;
 * the remaining actuator endpoints require OPERATIONS.
 *
 * <p>CORS is handled here (Spring Security's CorsFilter runs before authentication, so preflight requests never
 * need a token) from the same {@code CORS_ALLOWED_ORIGINS} variable as before.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** REST API v1 (rest/BaggageRestController): the same roles as the GraphQL operations, decided by URL. */
    private static final String REST = "/api/baggage/v1";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
                                            @Value("${spring.graphql.path}") String graphqlPath,
                                            ObjectMapper objectMapper) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        jwtConverter.setPrincipalClaimName("preferred_username");   // Authentication.getName() -> "anna"/"ops"
        ProblemAuthHandlers problems = new ProblemAuthHandlers(objectMapper);   // 401/403 as problem+json

        http
                .csrf(AbstractHttpConfigurer::disable)              // stateless bearer-token API, no cookies
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(graphqlPath).permitAll()       // per-operation checks via @PreAuthorize
                        .requestMatchers("/graphiql", "/graphiql/**").permitAll()
                        // REST v1 - public lookup, PASSENGER/OPERATIONS for the booking's bags and registration,
                        // OPERATIONS for status changes (mirrors the @PreAuthorize rules of the GraphQL API)
                        .requestMatchers(HttpMethod.GET, REST + "/baggage/*").permitAll()
                        .requestMatchers(HttpMethod.GET, REST + "/bookings/*/baggage")
                                .hasAnyRole("PASSENGER", "OPERATIONS")
                        .requestMatchers(HttpMethod.POST, REST + "/baggage").hasAnyRole("PASSENGER", "OPERATIONS")
                        .requestMatchers(HttpMethod.PATCH, REST + "/baggage/*/status").hasRole("OPERATIONS")
                        .anyRequest().hasRole("OPERATIONS"))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /** One CORS policy for every path, built from {@code CORS_ALLOWED_ORIGINS} (comma separated). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));   // PATCH: REST status update
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
