package com.interviewprep.orders.springapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Entry point. {@code @SpringBootApplication} is itself a meta-annotation
 * bundling three things every Boot app needs:
 *
 * <ul>
 *   <li>{@code @Configuration} — this class can itself declare {@code @Bean} methods</li>
 *   <li>{@code @EnableAutoConfiguration} — Boot inspects the classpath (finds
 *       spring-boot-starter-web? register embedded Tomcat + DispatcherServlet.
 *       Finds spring-boot-starter-data-jpa + a JDBC driver? register a
 *       DataSource, EntityManagerFactory, transaction manager, etc.) and
 *       configures beans automatically — this is "convention over
 *       configuration" and the single biggest reason Spring Boot exists:
 *       plain Spring required wiring all of this by hand in XML or
 *       {@code @Configuration} classes.</li>
 *   <li>{@code @ComponentScan} — scans this package and sub-packages for
 *       {@code @Component}/{@code @Service}/{@code @Repository}/{@code @Controller}
 *       classes and registers them as beans, which is WHY every class in
 *       this module lives under {@code com.interviewprep.orders.springapp}
 *       rather than scattered across unrelated root packages.</li>
 * </ul>
 *
 * WHY {@code @EnableCaching} IS ALSO HERE (Module 8): it switches on Spring's
 * caching infrastructure — specifically, it registers the AOP proxy machinery
 * that intercepts calls to {@code @Cacheable}/{@code @CacheEvict}/{@code @CachePut}
 * annotated methods (see {@code ProductService}) and redirects them through
 * a {@code CacheManager} (see {@code config/CacheConfig.java} for the Redis-backed
 * one this module configures) before the real method body ever runs. Without
 * this annotation, {@code @Cacheable} is silently a no-op — a very common
 * "why isn't my cache working" bug for people new to Spring.
 *
 * BUILD/RUN (once you have JDK 21 + Maven 3.9+ locally):
 *   cd spring
 *   docker compose up -d                 # start Postgres + Redis
 *   mvn spring-boot:run                  # or: mvn clean package && java -jar target/spring-orders-api-0.0.1-SNAPSHOT.jar
 *   open http://localhost:8080/swagger-ui.html
 */
@SpringBootApplication
@EnableCaching
public class SpringOrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringOrdersApplication.class, args);
    }
}
