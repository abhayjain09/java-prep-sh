package com.interviewprep.orders.springapp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Customizes the OpenAPI document's top-level metadata. springdoc-openapi
 * needs NO configuration at all to work (its auto-configuration scans every
 * {@code @RestController} and generates a spec from method signatures +
 * {@code @Operation}/{@code @ApiResponse} annotations automatically) — this
 * bean only adds human-facing info (title/description/contact) that would
 * otherwise default to generic placeholder text in the generated
 * /v3/api-docs and Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ordersApiOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order/Inventory API")
                        .version("v1")
                        .description("Module 5/8 REST API over the Order/Inventory domain — "
                                + "CRUD, validation, pagination/sorting/filtering, versioning, "
                                + "HATEOAS, and Redis-backed caching.")
                        .contact(new Contact()
                                .name("java-prep interview curriculum")
                                .url("https://github.com/")));
    }
}
