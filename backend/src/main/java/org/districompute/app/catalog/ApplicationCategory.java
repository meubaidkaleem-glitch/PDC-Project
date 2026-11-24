package org.districompute.app.catalog;

import java.util.Set;
import org.districompute.core.algorithm.AlgorithmCategory;

/**
 * The task categories the application presents. Each maps to Core categories; a category without registered Core
 * algorithms is shown as unavailable, never as functional.
 */
public enum ApplicationCategory {

    MATRIX("Matrix and linear algebra", "Dense matrix addition and multiplication on generated or uploaded matrices.",
            Set.of(AlgorithmCategory.LINEAR_ALGEBRA)),
    NUMERICAL("Mathematical and numerical computation",
            "Integration, polynomial evaluation, root finding, Monte Carlo estimation and prime counting.",
            Set.of(AlgorithmCategory.NUMERICAL, AlgorithmCategory.NUMBER_THEORY)),
    DATA("Data processing", "Sorting, filtering and statistical aggregation of generated or uploaded data.",
            Set.of(AlgorithmCategory.DATA_PROCESSING, AlgorithmCategory.SORTING)),
    IMAGE_SIGNAL("Image and signal processing", "No image or signal algorithm is registered in DistriCompute-Core.",
            Set.of()),
    SIMULATION("Scientific simulations", "No simulation algorithm is registered in DistriCompute-Core.", Set.of()),
    CUSTOM("Custom registered algorithms",
            "Algorithms registered with Core's service loader (AlgorithmProvider) in addition to the built-in ones.",
            Set.of());

    private final String displayName;
    private final String description;
    private final Set<AlgorithmCategory> coreCategories;

    ApplicationCategory(String displayName, String description, Set<AlgorithmCategory> coreCategories) {
        this.displayName = displayName;
        this.description = description;
        this.coreCategories = coreCategories;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /**
     * The application category of a Core algorithm.
     *
     * @param coreCategory Core's category
     * @param builtIn whether the algorithm ships with Core
     * @return the category
     */
    public static ApplicationCategory of(AlgorithmCategory coreCategory, boolean builtIn) {
        if (!builtIn) {
            return CUSTOM;
        }
        for (ApplicationCategory category : values()) {
            if (category.coreCategories.contains(coreCategory)) {
                return category;
            }
        }
        return CUSTOM;
    }
}
