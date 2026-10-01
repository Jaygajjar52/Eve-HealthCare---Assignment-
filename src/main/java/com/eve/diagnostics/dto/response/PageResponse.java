package com.eve.diagnostics.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Wrapper around a Spring Data page. A {@code Page} cannot be returned straight
 * from a controller because it serialises its internal structure and every
 * element type, so it is converted into this small, stable shape instead.
 *
 * <p>Page numbers here are zero-based, matching what {@code ?page=} sends.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}