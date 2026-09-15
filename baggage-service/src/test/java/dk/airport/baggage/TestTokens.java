package dk.airport.baggage;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test replacement for Keycloak (dev plan DP-04): mints JWTs with the same claims Keycloak puts in a token for
 * the {@code airport} realm and provides a {@link JwtDecoder} that trusts the matching test key. Import it with
 * {@code @Import(TestTokens.class)}; the auto-configured decoder (JWK_SET_URI) then backs off, so no Keycloak is
 * needed for tests. Everything else - the Bearer header, issuer check, role mapping, {@code @PreAuthorize} - runs
 * exactly as in production.
 *
 * <pre>
 *   GraphQlTester asOps = httpGraphQlTester.mutate()
 *           .header(HttpHeaders.AUTHORIZATION, TestTokens.operations()).build();
 * </pre>
 */
@TestConfiguration
public class TestTokens {

    /** Must equal spring.security.oauth2.resourceserver.jwt.issuer-uri as the tests see it (application.yml). */
    public static final String ISSUER = "http://localhost:8180/realms/airport";

    private static final RSAKey KEY = generateKey();

    @Bean
    JwtDecoder testJwtDecoder() throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    /** {@code Authorization} header value for the test user anna (role PASSENGER). */
    public static String passenger() {
        return bearer("anna", "anna@example.com", "PASSENGER");
    }

    /** {@code Authorization} header value for the test user ops (role OPERATIONS). */
    public static String operations() {
        return bearer("ops", "ops@example.com", "OPERATIONS");
    }

    /** {@code Authorization} header value for any user/roles, e.g. a PASSENGER with another e-mail. */
    public static String bearer(String username, String email, String... roles) {
        return "Bearer " + token(username, email, ISSUER, 3600, roles);
    }

    /** Raw signed token; {@code issuer}/{@code lifetimeSeconds} can be varied to test rejected tokens. */
    public static String token(String username, String email, String issuer, long lifetimeSeconds, String... roles) {
        long now = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.nameUUIDFromBytes(username.getBytes()).toString())
                .issueTime(new Date(now))
                .expirationTime(new Date(now + lifetimeSeconds * 1000))
                .claim("typ", "Bearer")
                .claim("azp", "airport-frontend")
                .claim("preferred_username", username)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
