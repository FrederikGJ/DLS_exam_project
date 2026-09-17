package dk.airport.flight.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * OAuth2 resource-server security for the GraphQL API (dev plan DP-02).
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
 * the remaining actuator endpoints require OPERATIONS, except /actuator/prometheus (scraped by Prometheus).
 *
 * <p>CORS is handled here (Spring Security's CorsFilter runs before authentication, so preflight requests never
 * need a token) from the same {@code CORS_ALLOWED_ORIGINS} variable as before.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
                                            @Value("${spring.graphql.path}") String graphqlPath) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
        jwtConverter.setPrincipalClaimName("preferred_username");   // Authentication.getName() -> "anna"/"ops"

        http
                .csrf(AbstractHttpConfigurer::disable)              // stateless bearer-token API, no cookies
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // scraped by Prometheus without a token; operational numbers only, and the Ingress does not
                        // route /actuator at all (dev plan DP-33)
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers(graphqlPath).permitAll()       // per-operation checks via @PreAuthorize
                        .requestMatchers("/graphiql", "/graphiql/**").permitAll()
                        .anyRequest().hasRole("OPERATIONS"))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    /** One CORS policy for every path, built from {@code CORS_ALLOWED_ORIGINS} (comma separated). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
