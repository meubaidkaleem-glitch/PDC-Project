package org.districompute.app.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page application's {@code index.html} for its client-side routes (for example
 * {@code /experiments/123}) when the frontend is packaged into the jar. API, actuator and documentation paths are
 * excluded, so unknown API paths still answer 404.
 */
@Controller
public class SpaController {

    private static final String SEGMENT = "^(?!api$|actuator$|v3$|swagger-ui$|assets$)[^.]*";

    /**
     * Forwards to the application shell.
     *
     * @return the forward
     */
    @GetMapping({"/", "/{a:" + SEGMENT + "}", "/{a:" + SEGMENT + "}/{b:[^.]*}",
        "/{a:" + SEGMENT + "}/{b:[^.]*}/{c:[^.]*}"})
    public String forward() {
        return "forward:/index.html";
    }
}
