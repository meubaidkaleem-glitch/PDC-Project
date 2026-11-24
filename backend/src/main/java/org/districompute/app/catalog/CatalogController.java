package org.districompute.app.catalog;

import java.util.List;
import org.districompute.app.catalog.CatalogViews.AlgorithmView;
import org.districompute.app.catalog.CatalogViews.CategoryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Algorithm catalog: {@code /api/v1/algorithms} and {@code /api/v1/categories}. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * Task categories.
     *
     * @return categories
     */
    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return catalog.categories();
    }

    /**
     * Registered algorithms.
     *
     * @param category optional category filter
     * @return algorithms
     */
    @GetMapping("/algorithms")
    public List<AlgorithmView> algorithms(@RequestParam(required = false) String category) {
        return catalog.algorithms().stream().filter(a -> category == null || a.category().equalsIgnoreCase(category))
                .toList();
    }

    /**
     * One algorithm.
     *
     * @param id Core id
     * @return the algorithm
     */
    @GetMapping("/algorithms/{id}")
    public AlgorithmView algorithm(@PathVariable String id) {
        return catalog.algorithm(id);
    }
}
