package com.demo.upimesh;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The whole ingestion suite (validation, atomic settlement, idempotency, nonce reuse, retryable failures) against a
 * real PostgreSQL instead of H2. Not part of the default build: it needs a server. Run it with an EMPTY test database:
 *
 *   UPI_TEST_PG_URL=postgres://user:password@localhost:5432/upimesh_test ./mvnw test -Dtest='Postgres*IT'
 *
 * The tests reset the data they use; do not point this at a database you care about.
 */
@EnabledIfEnvironmentVariable(named = "UPI_TEST_PG_URL", matches = ".+")
@SpringBootTest(properties = "DATABASE_URL=${UPI_TEST_PG_URL}")
class PostgresIngestionIT extends IngestionTest {
}
