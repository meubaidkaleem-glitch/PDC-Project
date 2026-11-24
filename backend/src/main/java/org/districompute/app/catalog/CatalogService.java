package org.districompute.app.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.districompute.app.api.ApiException;
import org.districompute.app.catalog.CatalogViews.AlgorithmView;
import org.districompute.app.catalog.CatalogViews.CategoryView;
import org.districompute.app.catalog.CatalogViews.ModeView;
import org.districompute.app.catalog.CatalogViews.ParameterView;
import org.districompute.core.algorithm.AlgorithmDescriptor;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.ParameterType;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.registry.BuiltInAlgorithms;
import org.springframework.stereotype.Service;

/** Core's algorithm catalog, presented in the application's categories. Nothing here is hard-coded per algorithm. */
@Service
public class CatalogService {

    private final AlgorithmRegistry registry;
    private final Set<String> builtIn;

    CatalogService(AlgorithmRegistry registry) {
        this.registry = registry;
        this.builtIn = new BuiltInAlgorithms().algorithms().stream().map(a -> a.descriptor().id().value())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The categories, in the order the specification lists them.
     *
     * @return categories with their algorithm counts
     */
    public List<CategoryView> categories() {
        List<AlgorithmView> algorithms = algorithms();
        List<CategoryView> views = new ArrayList<>();
        for (ApplicationCategory category : ApplicationCategory.values()) {
            int count = (int) algorithms.stream().filter(a -> a.category().equals(category.name())).count();
            views.add(new CategoryView(category.name(), category.displayName(), category.description(), count,
                    count > 0));
        }
        return views;
    }

    /**
     * Every registered algorithm.
     *
     * @return algorithms, by category and name
     */
    public List<AlgorithmView> algorithms() {
        return registry.catalog().stream().map(this::view)
                .sorted(Comparator.comparing(AlgorithmView::category).thenComparing(AlgorithmView::displayName))
                .toList();
    }

    /**
     * One algorithm.
     *
     * @param id Core id
     * @return the algorithm
     * @throws ApiException 404 if unknown
     */
    public AlgorithmView algorithm(String id) {
        return find(id).map(this::view).orElseThrow(() -> ApiException.notFound("Algorithm", id));
    }

    /**
     * Core's information about an algorithm.
     *
     * @param id Core id
     * @return the information, if registered
     */
    public Optional<AlgorithmInfo> find(String id) {
        try {
            return registry.info(AlgorithmId.of(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The application category of an algorithm.
     *
     * @param info Core's information
     * @return the category
     */
    public ApplicationCategory categoryOf(AlgorithmInfo info) {
        return ApplicationCategory.of(info.descriptor().category(), builtIn.contains(info.descriptor().id().value()));
    }

    private AlgorithmView view(AlgorithmInfo info) {
        AlgorithmDescriptor d = info.descriptor();
        List<ParameterView> parameters = d.parameters().stream().map(CatalogService::parameter).toList();
        List<ModeView> modes = new ArrayList<>();
        for (ExecutionMode mode : ExecutionMode.values()) {
            boolean supported = info.capabilities().supportedModes().contains(mode);
            String reason = null;
            boolean executable = supported;
            if (mode == ExecutionMode.GPU) {
                executable = false;
                reason = "No GPU backend: " + registry.gpuStatus().summary();
            } else if (mode == ExecutionMode.HYBRID) {
                executable = false;
                reason = "Hybrid CPU/GPU/distributed execution is not implemented by the components";
            } else if (!supported) {
                reason = "Core does not support " + mode + " for this algorithm";
            }
            modes.add(new ModeView(mode.name(), supported, executable, reason));
        }
        List<String> inputs = parameters.stream().filter(ParameterView::uploadable).map(ParameterView::name).toList();
        return new AlgorithmView(d.id().value(), d.version().toString(), d.displayName(), d.description(),
                categoryOf(info).name(), d.category().name(), builtIn.contains(d.id().value()), parameters,
                d.resultType(), d.verificationMethod(), d.limitations(), info.capabilities().partitionStrategy().name(),
                info.capabilities().distributable(), modes, inputs);
    }

    private static ParameterView parameter(ParameterSpec p) {
        boolean array = p.type() == ParameterType.INTEGER_ARRAY || p.type() == ParameterType.DECIMAL_ARRAY;
        return new ParameterView(p.name(), p.type().name(), p.description(), p.required(), p.defaultValue(),
                p.minimum(), p.maximum(), p.allowedValues(), p.minLength(), p.maxLength(), p.unit(), array);
    }
}
