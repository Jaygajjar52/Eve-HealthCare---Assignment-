package com.eve.diagnostics.controller;

import com.eve.diagnostics.dto.response.PageResponse;
import com.eve.diagnostics.dto.response.TestResponse;
import com.eve.diagnostics.service.CentreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The global test catalogue, kept separate from CentreController because /tests
 * is not scoped under a centre.
 */
@RestController
@RequestMapping("/tests")
@RequiredArgsConstructor
@Tag(name = "Diagnostic tests", description = "The global catalogue of bookable tests")
public class TestController {

    private final CentreService centreService;

    @GetMapping
    @Operation(summary = "List tests",
            description = "Public. Without ?name= the whole catalogue is returned.")
    public PageResponse<TestResponse> listTests(
            @RequestParam(required = false) String name,
            @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return centreService.listTests(name, pageable);
    }
}