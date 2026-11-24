package org.districompute.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.districompute.app.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Run-time settings and the server's configuration ({@code docs/configuration.md}). */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settings;
    private final CurrentUser currentUser;

    SettingsController(SettingsService settings, CurrentUser currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    /** @return settings and limits; the server's configuration for administrators */
    @GetMapping
    public Map<String, Object> get() {
        return settings.view(currentUser.isAdmin());
    }

    /**
     * A settings change.
     *
     * @param schedulingPolicy the coordinator's default scheduling policy
     */
    public record SettingsChange(@NotBlank String schedulingPolicy) {
    }

    /**
     * Changes settings (administrators).
     *
     * @param change the change
     * @return the new settings
     */
    @PatchMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> update(@Valid @RequestBody SettingsChange change) {
        return settings.setSchedulingPolicy(change.schedulingPolicy(), currentUser.require().getUsername(), true);
    }
}
