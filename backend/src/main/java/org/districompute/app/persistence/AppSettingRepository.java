package org.districompute.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Settings. */
public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
