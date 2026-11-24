package org.districompute.app.template;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.experiment.ExperimentAccess;
import org.districompute.app.experiment.ExperimentService;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.ExperimentTemplate;
import org.districompute.app.persistence.ExperimentTemplateRepository;
import org.districompute.app.security.AuditService;
import org.districompute.app.task.ExecutionSettings;
import org.districompute.app.task.TaskDraft;
import org.districompute.app.task.TaskFactory;
import org.districompute.app.template.TemplateViews.SaveTemplate;
import org.districompute.app.template.TemplateViews.TemplateView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal experiment templates: a validated task (algorithm, parameters, uploaded inputs, execution settings) to
 * start new experiments or batches from ({@code docs/batches-and-templates.md}).
 */
@Service
public class TemplateService {

    /** Most templates per user. */
    static final int MAX_TEMPLATES = 200;

    private final ExperimentTemplateRepository templates;
    private final TaskFactory tasks;
    private final ExperimentService experiments;
    private final ExperimentAccess access;
    private final AuditService audit;
    private final JsonSupport json;
    private final Clock clock;

    TemplateService(ExperimentTemplateRepository templates, TaskFactory tasks, ExperimentService experiments,
            ExperimentAccess access, AuditService audit, JsonSupport json, Clock clock) {
        this.templates = templates;
        this.tasks = tasks;
        this.experiments = experiments;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Saves a template after Core validated its task.
     *
     * @param request name, description and task
     * @param owner the user
     * @param admin whether the user is an administrator
     * @return the template
     */
    @Transactional
    public TemplateView save(SaveTemplate request, AppUser owner, boolean admin) {
        String name = request.name().strip();
        if (templates.existsByOwnerIdAndName(owner.getId(), name)) {
            throw ApiException.conflict("TEMPLATE_NAME_TAKEN", "You already have a template named " + name);
        }
        if (templates.countByOwnerId(owner.getId()) >= MAX_TEMPLATES) {
            throw ApiException.conflict("TOO_MANY_TEMPLATES", "You have " + MAX_TEMPLATES + " templates; delete one "
                    + "first");
        }
        tasks.prepare(request.task(), owner, admin);
        ExperimentTemplate template = new ExperimentTemplate(owner.getId(), clock.instant());
        template.setName(name);
        template.setDescription(request.description());
        template.setAlgorithmId(request.task().algorithm());
        template.setExecutionMode(request.task().execution().mode());
        template.setParameters(json.write(request.task().parameters()));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("execution", request.task().execution());
        configuration.put("inputs", request.task().inputs());
        template.setConfiguration(json.write(configuration));
        templates.save(template);
        audit.record(owner.getUsername(), "TEMPLATE_SAVED", "TEMPLATE", String.valueOf(template.getId()), name, null);
        return view(template);
    }

    /**
     * Saves the task of an experiment the user may see as a template.
     *
     * @param experimentId experiment
     * @param name template name
     * @param description description
     * @param owner the user
     * @param admin whether the user is an administrator
     * @return the template
     */
    @Transactional
    public TemplateView fromExperiment(String experimentId, String name, String description, AppUser owner,
            boolean admin) {
        TaskDraft draft = experiments.draftOf(access.require(experimentId));
        return save(new SaveTemplate(name, description, draft), owner, admin);
    }

    /**
     * The user's templates.
     *
     * @param owner the user
     * @return templates by name
     */
    @Transactional(readOnly = true)
    public List<TemplateView> list(AppUser owner) {
        return templates.findByOwnerIdOrderByNameAsc(owner.getId()).stream().map(this::view).toList();
    }

    /**
     * One of the user's templates.
     *
     * @param id template
     * @param owner the user
     * @return the template
     */
    @Transactional(readOnly = true)
    public TemplateView get(Long id, AppUser owner) {
        return view(require(id, owner));
    }

    /**
     * Deletes one of the user's templates.
     *
     * @param id template
     * @param owner the user
     */
    @Transactional
    public void delete(Long id, AppUser owner) {
        ExperimentTemplate template = require(id, owner);
        templates.delete(template);
        audit.record(owner.getUsername(), "TEMPLATE_DELETED", "TEMPLATE", String.valueOf(id), template.getName(),
                null);
    }

    private ExperimentTemplate require(Long id, AppUser owner) {
        ExperimentTemplate template = templates.findById(id).orElseThrow(() -> ApiException.notFound("Template", id));
        if (!template.getOwnerId().equals(owner.getId())) {
            throw ApiException.notFound("Template", id);
        }
        return template;
    }

    @SuppressWarnings("unchecked")
    private TemplateView view(ExperimentTemplate t) {
        Map<String, Object> configuration = json.map(t.getConfiguration());
        ExecutionSettings execution = json.read(json.write(configuration.get("execution")), ExecutionSettings.class);
        Map<String, String> inputs = configuration.get("inputs") instanceof Map<?, ?> m
                ? (Map<String, String>) m : Map.of();
        TaskDraft task = new TaskDraft(t.getAlgorithmId(), json.map(t.getParameters()), inputs, execution);
        return new TemplateView(t.getId(), t.getName(), t.getDescription(), t.getAlgorithmId(), t.getExecutionMode(),
                task, t.getCreatedAt());
    }
}
