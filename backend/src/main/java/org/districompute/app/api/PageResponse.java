package org.districompute.app.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A page of results.
 *
 * @param items the items
 * @param page zero-based page number
 * @param size page size
 * @param totalItems total matching items
 * @param totalPages total pages
 * @param <T> item type
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    /**
     * Maps a Spring Data page.
     *
     * @param page the page
     * @param mapper item mapper
     * @param <E> entity type
     * @param <T> item type
     * @return the response
     */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
