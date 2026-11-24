package org.districompute.app.security;

import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads accounts from the {@code app_users} table. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException(username));
        return User.withUsername(user.getUsername()).password(user.getPasswordHash()).roles(user.getRole().name())
                .disabled(!user.isEnabled()).build();
    }
}
