package org.districompute.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the DistriCompute application backend. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DistriComputeApplication {

    /**
     * Starts the backend.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(DistriComputeApplication.class, args);
    }
}
