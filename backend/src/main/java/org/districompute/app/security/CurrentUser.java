package org.districompute.app.security;

import org.districompute.app.api.ApiException;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.AppUserRepository;
import org.districompute.app.persistence.Enums.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The authenticated user of the current request. */
@Component
public class CurrentUser {

    private final AppUserRepository users;

    CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    /**
     * The current account.
     *
     * @return the account
     * @throws ApiException 401-equivalent if nobody is authenticated
     */
    public AppUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw ApiException.forbidden("Not authenticated");
        }
        return users.findByUsername(auth.getName()).orElseThrow(() -> ApiException.forbidden("Unknown account"));
    }

    /**
     * Whether the current user is an administrator.
     *
     * @return {@code true} for {@code ADMIN}
     */
    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Description of a session.
     *
     * @param auth authentication
     * @return the session view
     */
    public SessionView session(Authentication auth) {
        AppUser user = users.findByUsername(auth.getName()).orElseThrow();
        return new SessionView(true, user.getId(), user.getUsername(), user.getRole());
    }

    /**
     * The signed-in user as the API shows it.
     *
     * @param authenticated whether a user is signed in
     * @param id account id
     * @param username login name
     * @param role role
     */
    public record SessionView(boolean authenticated, Long id, String username, Role role) {
    }
}
