package org.districompute.app.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Application accounts. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * Finds an account by login name.
     *
     * @param username login name
     * @return the account
     */
    Optional<AppUser> findByUsername(String username);
}
