package org.districompute.app.catalog;

import java.util.List;

/** API views of Core's catalog. */
public final class CatalogViews {

    private CatalogViews() {
    }

    /**
     * A task category.
     *
     * @param id category id
     * @param name display name
     * @param description description
     * @param algorithmCount registered algorithms
     * @param available whether at least one algorithm is registered
     */
    public record CategoryView(String id, String name, String description, int algorithmCount, boolean available) {
    }

    /**
     * One parameter, as Core's schema defines it.
     *
     * @param name name
     * @param type Core parameter type
     * @param description description
     * @param required whether required
     * @param defaultValue default
     * @param minimum minimum
     * @param maximum maximum
     * @param allowedValues allowed values of an enumeration
     * @param minLength minimum array length
     * @param maxLength maximum array length
     * @param unit unit
     * @param uploadable whether the value can come from an uploaded CSV file
     */
    public record ParameterView(String name, String type, String description, boolean required, Object defaultValue,
            Number minimum, Number maximum, List<String> allowedValues, Integer minLength, Integer maxLength,
            String unit, boolean uploadable) {
    }

    /**
     * An execution mode and whether this installation can run it.
     *
     * @param mode Core mode name
     * @param supportedByAlgorithm whether Core supports the mode for the algorithm
     * @param executable whether the application can execute it now (implemented, and supported)
     * @param reason why not, if not executable
     */
    public record ModeView(String mode, boolean supportedByAlgorithm, boolean executable, String reason) {
    }

    /**
     * An algorithm.
     *
     * @param id Core id
     * @param version Core version
     * @param displayName name
     * @param description description
     * @param category application category id
     * @param coreCategory Core category
     * @param builtIn whether it ships with Core
     * @param parameters parameter schema
     * @param resultType Core result type
     * @param verificationMethod how Core verifies results
     * @param limitations Core's documented limitations
     * @param partitionStrategy Core partition strategy
     * @param distributable whether partitions can run on workers
     * @param modes execution modes
     * @param inputParameters parameters that accept uploaded data
     */
    public record AlgorithmView(String id, String version, String displayName, String description, String category,
            String coreCategory, boolean builtIn, List<ParameterView> parameters, String resultType,
            String verificationMethod, List<String> limitations, String partitionStrategy, boolean distributable,
            List<ModeView> modes, List<String> inputParameters) {
    }
}
