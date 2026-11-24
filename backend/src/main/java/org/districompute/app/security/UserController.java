package org.districompute.app.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.districompute.app.api.ApiException;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.AppUserRepository;
import org.districompute.app.persistence.Enums.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Account management (administrators only). */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final Clock clock;

    UserController(AppUserRepository users, PasswordEncoder encoder, CurrentUser currentUser, AuditService audit,
            Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Lists accounts.
     *
     * @return accounts (never password hashes)
     */
    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::of).toList();
    }

    /**
     * Creates an account.
     *
     * @param request the account
     * @return the account
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserView create(@Valid @RequestBody NewUser request) {
        if (users.findByUsername(request.username()).isPresent()) {
            throw ApiException.conflict("USERNAME_TAKEN", "User name " + request.username() + " is already taken");
        }
        AppUser user = users.save(new AppUser(request.username(), encoder.encode(request.password()), request.role(),
                clock.instant()));
        audit.record(currentUser.require().getUsername(), "USER_CREATED", "USER", String.valueOf(user.getId()),
                request.username() + " as " + request.role(), null);
        return UserView.of(user);
    }

    /**
     * Changes an account's role or enables or disables it.
     *
     * @param id account id
     * @param request changes
     * @return the account
     */
    @PatchMapping("/{id}")
    @Transactional
    public UserView update(@PathVariable long id, @Valid @RequestBody UserChange request) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
        AppUser self = currentUser.require();
        if (user.getId().equals(self.getId()) && (request.enabled() == Boolean.FALSE
                || (request.role() != null && request.role() != Role.ADMIN))) {
            throw ApiException.badRequest("SELF_LOCKOUT", "You cannot disable or demote your own account");
        }
        if (request.role() != null) {
            user.setRole(request.role());
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (request.password() != null) {
            user.setPasswordHash(encoder.encode(request.password()));
        }
        audit.record(self.getUsername(), "USER_CHANGED", "USER", String.valueOf(id),
                "role=" + request.role() + " enabled=" + request.enabled()
                        + (request.password() != null ? " password reset" : ""), null);
        return UserView.of(users.save(user));
    }

    /**
     * An account as the API shows it.
     *
     * @param id id
     * @param username login name
     * @param role role
     * @param enabled whether it can sign in
     * @param createdAt creation time
     */
    public record UserView(Long id, String username, Role role, boolean enabled, Instant createdAt) {
        static UserView of(AppUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(),
                    user.getCreatedAt());
        }
    }

    /**
     * A new account.
     *
     * @param username login name
     * @param password initial password
     * @param role role
     */
    public record NewUser(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
            @NotBlank @Size(min = 12, max = 128) String password, @NotNull Role role) {
    }

    /**
     * Account changes; {@code null} members are left unchanged.
     *
     * @param role new role
     * @param enabled whether the account can sign in
     * @param password new password
     */
    public record UserChange(Role role, Boolean enabled, @Size(min = 12, max = 128) String password) {
    }
}
