package com.interviewprep.orders.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration-tier example (see testing/diagrams/test-pyramid.md): boots a REAL
 * PostgreSQL database in a disposable Docker container via Testcontainers, runs an
 * inline schema "migration," and exercises it with plain JDBC.
 *
 * SCOPE NOTE -- READ BEFORE ASKING "why not just test the JPA repositories?": at the
 * time this module was authored, database/ (Module 7, real schema/SQL) and spring/
 * (Module 5, Spring Data JPA repositories) are separate, concurrently-in-progress
 * modules in this curriculum and this file deliberately does not import or depend
 * on anything from either of them -- they may not exist yet, or may change shape
 * before they're finished. Instead, this test defines its OWN minimal inline schema
 * below (in {@link #startContainerAndRunMigration()}) that approximates the
 * Order/Inventory shape from java-basics closely enough to demonstrate the
 * Testcontainers mechanics end-to-end against a real database engine.
 *
 * In the FINISHED curriculum, once Module 5 (spring/) and Module 7 (database/) both
 * exist, this test's role would be superseded by (or extended into) an integration
 * test of the actual Spring Data JPA repositories -- reusing this exact same
 * Testcontainers container-lifecycle setup, just pointed at Spring's
 * DataSource/EntityManager instead of a raw JDBC Connection. The mechanics shown
 * here (container lifecycle, schema setup, real-engine query behavior) carry over
 * unchanged; only "what runs the query" would change.
 *
 * WHY POSTGRES-IN-A-CONTAINER INSTEAD OF AN IN-MEMORY H2 DATABASE: see README.md,
 * "Testcontainers vs. H2" for the full argument -- in short, H2's SQL dialect and
 * constraint-enforcement behavior can meaningfully diverge from real PostgreSQL, so
 * a test passing against H2 is not proof the same query works against production.
 * This class's {@link #foreignKeyConstraintRejectsAnOrderLineForAnUnknownProduct()}
 * test is a concrete example: it depends on PostgreSQL actually enforcing a foreign
 * key constraint the exact way production does.
 */
@Testcontainers
@DisplayName("OrderRepositoryIT (Testcontainers + real PostgreSQL)")
class OrderRepositoryIT {

    /**
     * @Container (combined with the class-level @Testcontainers extension) makes
     * JUnit 5 start this container before any @Test in this class runs and stop it
     * after the last one finishes -- equivalent in spirit to a class-scoped
     * @BeforeAll/@AfterAll pair, but handled by the extension so we can't forget to
     * call .stop(). "postgres:16-alpine" pins both the major version (so behavior is
     * reproducible across machines/CI runs) and uses the small Alpine-based image
     * (faster to pull than the full image) for a disposable test container.
     */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("orders_test")
                    .withUsername("test")
                    .withPassword("test");

    private static Connection connection;

    @BeforeAll
    static void startContainerAndRunMigration() throws SQLException {
        // By the time this runs, @Testcontainers has already started POSTGRES and
        // waited for it to report ready (Testcontainers' Postgres module has a
        // built-in wait-strategy tuned to Postgres's actual startup log line, not a
        // fixed sleep). getJdbcUrl()/getUsername()/getPassword() reflect the
        // container's randomly assigned host port -- never hard-code a port here, it
        // changes on every run.
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        // MINIMAL INLINE SCHEMA -- not Flyway/Liquibase, and not Module 5's real JPA
        // entities (see class Javadoc above for why). Just enough structure to mirror
        // java-basics' Customer / Product / Order / OrderLine shape and exercise a
        // real join + a real constraint against a real Postgres engine.
        try (Statement schema = connection.createStatement()) {
            schema.execute("""
                    CREATE TABLE customers (
                        id    VARCHAR(64) PRIMARY KEY,
                        name  VARCHAR(255) NOT NULL,
                        email VARCHAR(255) NOT NULL
                    )
                    """);
            schema.execute("""
                    CREATE TABLE products (
                        sku   VARCHAR(64) PRIMARY KEY,
                        name  VARCHAR(255) NOT NULL,
                        price NUMERIC(12,2) NOT NULL CHECK (price >= 0)
                    )
                    """);
            schema.execute("""
                    CREATE TABLE orders (
                        id          VARCHAR(64) PRIMARY KEY,
                        customer_id VARCHAR(64) NOT NULL REFERENCES customers(id),
                        status      VARCHAR(32) NOT NULL
                    )
                    """);
            schema.execute("""
                    CREATE TABLE order_lines (
                        order_id VARCHAR(64) NOT NULL REFERENCES orders(id),
                        sku      VARCHAR(64) NOT NULL REFERENCES products(sku),
                        quantity INTEGER NOT NULL CHECK (quantity > 0),
                        PRIMARY KEY (order_id, sku)
                    )
                    """);

            schema.execute("""
                    INSERT INTO customers (id, name, email)
                    VALUES ('CUST-1', 'Ada Lovelace', 'ada@example.com')
                    """);
            schema.execute("""
                    INSERT INTO products (sku, name, price) VALUES
                        ('SKU-WIDGET', 'Widget', 9.99),
                        ('SKU-GADGET', 'Gadget', 19.99)
                    """);
            schema.execute("""
                    INSERT INTO orders (id, customer_id, status)
                    VALUES ('ORD-1', 'CUST-1', 'PENDING')
                    """);
            schema.execute("""
                    INSERT INTO order_lines (order_id, sku, quantity) VALUES
                        ('ORD-1', 'SKU-WIDGET', 2),
                        ('ORD-1', 'SKU-GADGET', 1)
                    """);
        }
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        // POSTGRES itself is stopped automatically by the @Testcontainers extension
        // after this class's tests finish -- no manual POSTGRES.stop() needed.
    }

    @Test
    @DisplayName("sanity check: the container is genuinely PostgreSQL, not an emulation of it")
    void containerReportsPostgresEngine() throws SQLException {
        // This is the guarantee an in-memory H2 database (even in "PostgreSQL
        // compatibility mode") cannot give you -- this metadata call proves the code
        // below runs against the real engine, not a dialect approximation of it.
        DatabaseMetaData meta = connection.getMetaData();
        assertThat(meta.getDatabaseProductName()).isEqualTo("PostgreSQL");
    }

    @Test
    @DisplayName("a join + SUM query over order_lines/products matches the domain's own BigDecimal arithmetic")
    void joinQueryComputesOrderTotalMatchingDomainArithmetic() throws SQLException {
        // Cross-checks a basic query against the same arithmetic java-basics'
        // OrderLine.lineTotal()/Order.totalAmount() would produce for the equivalent
        // in-memory objects: (2 * 9.99) + (1 * 19.99) = 39.97.
        String sql = """
                SELECT SUM(p.price * ol.quantity) AS total
                FROM order_lines ol
                JOIN products p ON p.sku = ol.sku
                WHERE ol.order_id = ?
                """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, "ORD-1");
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                BigDecimal total = rs.getBigDecimal("total");
                assertThat(total).isEqualByComparingTo("39.97");
            }
        }
    }

    @Test
    @DisplayName("a foreign key constraint really is enforced by the engine, not just assumed")
    void foreignKeyConstraintRejectsAnOrderLineForAnUnknownProduct() {
        // This is precisely the class of test that gives FALSE confidence against
        // H2: whether a REFERENCES constraint is enforced, and how strictly, is a
        // real-engine behavior. Running this against actual PostgreSQL means a
        // passing test here is evidence the constraint will also hold in production
        // (which very likely also runs PostgreSQL) -- not just evidence it holds
        // against whatever subset of the SQL standard H2 chose to implement.
        assertThatThrownBy(() -> {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                        INSERT INTO order_lines (order_id, sku, quantity)
                        VALUES ('ORD-1', 'SKU-DOES-NOT-EXIST', 1)
                        """);
            }
        }).isInstanceOf(SQLException.class);
    }
}
