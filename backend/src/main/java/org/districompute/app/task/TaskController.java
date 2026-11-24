package org.districompute.app.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.districompute.app.advisor.AdviceView;
import org.districompute.app.advisor.AdvisorService;
import org.districompute.app.api.ApiException;
import org.districompute.app.catalog.CatalogService;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.security.CurrentUser;
import org.districompute.app.task.InputStore.InputMeta;
import org.districompute.app.task.TaskFactory.PreparedTask;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.ParameterType;
import org.districompute.core.registry.AlgorithmInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Task definition support: validation and analysis before submission, uploaded inputs, and the restricted
 * polynomial parser. Nothing is executed by these endpoints.
 */
@RestController
public class TaskController {

    private final TaskFactory tasks;
    private final AdvisorService advisor;
    private final InputStore inputs;
    private final CatalogService catalog;
    private final CurrentUser currentUser;

    TaskController(TaskFactory tasks, AdvisorService advisor, InputStore inputs, CatalogService catalog,
            CurrentUser currentUser) {
        this.tasks = tasks;
        this.advisor = advisor;
        this.inputs = inputs;
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    /**
     * Validates a task with the application's limits and Core's validation.
     *
     * @param draft the task
     * @return the validated task's workload (400 with every violation otherwise)
     */
    @PostMapping("/api/v1/tasks/validate")
    public Validation validate(@Valid @RequestBody TaskDraft draft) {
        PreparedTask task = prepare(draft);
        return new Validation(true, task.info().descriptor().id().value(), task.algorithmVersion(),
                task.category().name(), task.mode().name(), task.inputSource().name(),
                AdvisorService.workload(task.profile()), task.settings());
    }

    /**
     * Validates a task and advises on its execution strategy.
     *
     * @param draft the task
     * @return the advice
     */
    @PostMapping("/api/v1/tasks/analyze")
    public AdviceView analyze(@Valid @RequestBody TaskDraft draft) {
        return advisor.advise(prepare(draft));
    }

    /**
     * Uploads a CSV or text file of numbers for an array parameter.
     *
     * @param file the file
     * @param algorithm Core algorithm id
     * @param parameter Core array parameter
     * @return the stored input's metadata
     * @throws IOException if storage fails
     */
    @PostMapping(path = "/api/v1/inputs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public InputMeta upload(@RequestParam("file") MultipartFile file, @RequestParam String algorithm,
            @RequestParam String parameter) throws IOException {
        AlgorithmInfo info = catalog.find(algorithm).orElseThrow(() -> ApiException.notFound("Algorithm", algorithm));
        ParameterSpec spec = info.descriptor().parameters().stream().filter(p -> p.name().equals(parameter))
                .findFirst().orElseThrow(() -> ApiException.badRequest("NOT_AN_INPUT", parameter
                        + " is not a parameter of " + algorithm));
        boolean integral = spec.type() == ParameterType.INTEGER_ARRAY;
        if (!integral && spec.type() != ParameterType.DECIMAL_ARRAY) {
            throw ApiException.badRequest("NOT_AN_INPUT", parameter + " does not accept uploaded values");
        }
        int max = spec.maxLength() == null ? 1_000_000 : spec.maxLength();
        return inputs.store(file, currentUser.require(), algorithm, parameter, integral, max);
    }

    /**
     * An uploaded input's metadata.
     *
     * @param id upload id
     * @return the metadata
     */
    @GetMapping("/api/v1/inputs/{id}")
    public InputMeta input(@PathVariable String id) {
        return inputs.meta(id, currentUser.require(), currentUser.isAdmin());
    }

    /**
     * Parses a polynomial such as {@code 3x^2 - 2x + 1} into Core's ascending coefficients. The text is parsed by a
     * restricted grammar, never evaluated.
     *
     * @param request the expression
     * @return the coefficients and a normalized form
     */
    @PostMapping("/api/v1/expressions/polynomial")
    public Polynomial polynomial(@Valid @RequestBody PolynomialRequest request) {
        char variable = request.variable() == null ? 'x' : request.variable().charAt(0);
        try {
            double[] coefficients = PolynomialParser.parse(request.expression(), variable);
            return new Polynomial(coefficients, coefficients.length - 1, PolynomialParser.format(coefficients,
                    variable));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("The polynomial is invalid", java.util.List.of(
                    new ApiException.FieldViolation("expression", "INVALID_EXPRESSION", e.getMessage())));
        }
    }

    private PreparedTask prepare(TaskDraft draft) {
        AppUser user = currentUser.require();
        return tasks.prepare(draft, user, currentUser.isAdmin());
    }

    /**
     * A validated task.
     *
     * @param valid always {@code true} (invalid tasks are answered with 400)
     * @param algorithm Core id
     * @param algorithmVersion resolved version
     * @param category application category
     * @param mode execution mode
     * @param inputSource input source
     * @param workload Core's workload analysis
     * @param settings the settings that apply to the mode
     */
    public record Validation(boolean valid, String algorithm, String algorithmVersion, String category, String mode,
            String inputSource, AdviceView.WorkloadView workload, ExecutionSettings settings) {
    }

    /**
     * A polynomial to parse.
     *
     * @param expression the expression
     * @param variable the variable (one letter, default {@code x})
     */
    public record PolynomialRequest(@NotBlank @Size(max = PolynomialParser.MAX_LENGTH) String expression,
            @Pattern(regexp = "[A-Za-z]") String variable) {
    }

    /**
     * A parsed polynomial.
     *
     * @param coefficients ascending coefficients
     * @param degree degree
     * @param normalized normalized text
     */
    public record Polynomial(double[] coefficients, int degree, String normalized) {
    }
}
