package org.districompute.app.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.districompute.app.api.ApiException;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session information. Login ({@code POST /api/v1/auth/login}, form fields {@code username} and {@code password})
 * and logout ({@code POST /api/v1/auth/logout}) are handled by Spring Security ({@link SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final CurrentUser currentUser;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    AuthController(CurrentUser currentUser, AppUserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.currentUser = currentUser;
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    /**
     * The current session; also sets the CSRF cookie for the SPA.
     *
     * @param authentication the authentication, or {@code null}
     * @return the signed-in user, or {@code authenticated: false}
     */
    @GetMapping("/session")
    public CurrentUser.SessionView session(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            return new CurrentUser.SessionView(false, null, null, null);
        }
        return currentUser.session(authentication);
    }

    /**
     * Changes the current user's password.
     *
     * @param request old and new password
     * @return 204
     */
    @PostMapping("/password")
    @Transactional
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChange request) {
        AppUser user = currentUser.require();
        if (!encoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "The current password is not correct");
        }
        user.setPasswordHash(encoder.encode(request.newPassword()));
        users.save(user);
        audit.record(user.getUsername(), "PASSWORD_CHANGED", "USER", String.valueOf(user.getId()), null, null);
        return ResponseEntity.noContent().build();
    }

    /**
     * A password change.
     *
     * @param currentPassword current password
     * @param newPassword new password (12-128 characters)
     */
    public record PasswordChange(@NotBlank String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {
    }
}
