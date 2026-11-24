package org.districompute.app.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The application's clock (UTC), injectable so tests can control time. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    /**
     * The clock.
     *
     * @return the UTC system clock
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
