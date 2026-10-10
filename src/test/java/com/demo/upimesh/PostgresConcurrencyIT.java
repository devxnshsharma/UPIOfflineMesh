package com.demo.upimesh;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Duplicate storms and shared-account races against a real PostgreSQL. See {@link PostgresIngestionIT} for how to run it. */
@EnabledIfEnvironmentVariable(named = "UPI_TEST_PG_URL", matches = ".+")
@SpringBootTest(properties = "DATABASE_URL=${UPI_TEST_PG_URL}")
class PostgresConcurrencyIT extends ConcurrentIngestionTest {
}
