package com.interviewprep.orders.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for running this module standalone (see README.md's "Build &amp;
 * run" section for exact Maven commands). In a real multi-module deployment,
 * this class would not exist separately — the Order/Inventory REST
 * controllers from Module 5 ({@code spring/}) and this module's security
 * configuration would boot together as one Spring Boot application. It
 * exists here only so this module is independently runnable/gradable per
 * this task's scope boundaries (see the top of {@link AuthController} and
 * {@code pom.xml} for the same note).
 */
@SpringBootApplication
public class SecurityDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecurityDemoApplication.class, args);
    }
}
