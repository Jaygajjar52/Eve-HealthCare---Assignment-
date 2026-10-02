package com.eve.diagnostics.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Sends a bare visit to the base URL straight to Swagger UI.
 *
 * <p>Without this, starting the application lands on a 404, and the first thing
 * you have to do is remember that the docs live at /swagger-ui.html. Now
 * http://localhost:8080 just opens the API documentation.
 *
 * <p>Hidden from the OpenAPI document itself, since describing a redirect to the
 * documentation is not useful to a caller of the API.
 */
@Controller
@Hidden
public class HomeController {

    /**
     * GET / -> GET /swagger-ui.html
     */
    @GetMapping("/")
    public String home() {
        return "redirect:/swagger-ui.html";
    }
}