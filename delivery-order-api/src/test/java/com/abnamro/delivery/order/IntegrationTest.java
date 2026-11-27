package com.abnamro.delivery.order;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts the whole application on a random port against a real PostgreSQL (Testcontainers, migrated by Flyway)
 * and a WireMock Pricing. Both are started once and shared by all integration tests, so the Spring context is
 * reused; each test starts from empty tables, no stubs and a closed circuit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    protected static final WireMockServer PRICING = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        POSTGRES.start();
        PRICING.start();
    }

    @DynamicPropertySource
    static void pricingUrl(DynamicPropertyRegistry registry) {
        registry.add("delivery.pricing.base-url", PRICING::baseUrl);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private CircuitBreaker pricingCircuitBreaker;

    @BeforeEach
    void startClean() {
        jdbc.execute("TRUNCATE idempotency_record, delivery_order");
        PRICING.resetAll();
        pricingCircuitBreaker.reset();
    }

    protected int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
