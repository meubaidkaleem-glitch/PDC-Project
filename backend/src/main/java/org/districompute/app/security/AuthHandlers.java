package org.districompute.app.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** JSON responses for form login, and audit records of logins. */
@Component
public class AuthHandlers {

    private final JsonMapper json;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final LoginThrottle throttle;

    AuthHandlers(JsonMapper json, AuditService audit, CurrentUser currentUser, LoginThrottle throttle) {
        this.json = json;
        this.audit = audit;
        this.currentUser = currentUser;
        this.throttle = throttle;
    }

    void onSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        audit.record(authentication.getName(), "LOGIN", null, null, null, request.getRemoteAddr());
        throttle.succeeded(request.getParameter("username"), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), currentUser.session(authentication));
    }

    void onFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        audit.record(request.getParameter("username"), "LOGIN_FAILED", null, null, null, request.getRemoteAddr());
        throttle.failed(request.getParameter("username"), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Map.of("status", 401, "title", "Unauthorized", "code",
                "LOGIN_FAILED", "detail", "Wrong user name or password, or the account is disabled"));
    }
}
