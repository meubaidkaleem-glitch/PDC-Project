package org.districompute.app.template;

import jakarta.validation.Valid;
import java.util.List;
import org.districompute.app.security.CurrentUser;
import org.districompute.app.template.TemplateViews.SaveTemplate;
import org.districompute.app.template.TemplateViews.TemplateFromExperiment;
import org.districompute.app.template.TemplateViews.TemplateView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The user's experiment templates ({@code docs/batches-and-templates.md}). */
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    private final TemplateService templates;
    private final CurrentUser currentUser;

    TemplateController(TemplateService templates, CurrentUser currentUser) {
        this.templates = templates;
        this.currentUser = currentUser;
    }

    /** @return the user's templates by name */
    @GetMapping
    public List<TemplateView> list() {
        return templates.list(currentUser.require());
    }

    /**
     * Saves a template; Core validates its task first.
     *
     * @param request the template
     * @return 201 with the template
     */
    @PostMapping
    public ResponseEntity<TemplateView> save(@Valid @RequestBody SaveTemplate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(templates.save(request, currentUser.require(), currentUser.isAdmin()));
    }

    /**
     * Saves the task of an experiment as a template.
     *
     * @param experimentId experiment
     * @param request name and description
     * @return 201 with the template
     */
    @PostMapping("/from-experiment/{experimentId}")
    public ResponseEntity<TemplateView> fromExperiment(@PathVariable String experimentId,
            @Valid @RequestBody TemplateFromExperiment request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templates.fromExperiment(experimentId, request.name(),
                request.description(), currentUser.require(), currentUser.isAdmin()));
    }

    /**
     * One template.
     *
     * @param id template
     * @return the template
     */
    @GetMapping("/{id}")
    public TemplateView get(@PathVariable Long id) {
        return templates.get(id, currentUser.require());
    }

    /**
     * Deletes a template.
     *
     * @param id template
     * @return 204
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templates.delete(id, currentUser.require());
        return ResponseEntity.noContent().build();
    }
}
