package dk.airport.baggage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 401/403 answers from the security filter chain as {@code application/problem+json} with a {@code code}, in the
 * same shape as the REST API's other errors (see rest/RestExceptionHandler). The standard Bearer-token behaviour
 * (status and {@code WWW-Authenticate: Bearer error="invalid_token" ...}) is kept by delegating first; only the
 * body is added. Applies to every URL the chain protects, the GraphQL endpoint included.
 */
public class ProblemAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler deniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    public ProblemAuthHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        entryPoint.commence(request, response, ex);   // 401 + WWW-Authenticate
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication required: send a valid Bearer token");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        deniedHandler.handle(request, response, ex);   // 403 + WWW-Authenticate insufficient_scope
        write(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                "Your role does not allow this operation");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
                       String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", code);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("code", code);
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
