package com.demo.upimesh.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** DATABASE_URL (what hosting platforms provide) becomes the JDBC settings the application needs. */
class DatabaseUrlPostProcessorTest {

    @Test
    void platformStyleUrlBecomesAJdbcUrlWithCredentialsAndPersistentSchema() {
        Map<String, Object> p = DatabaseUrlPostProcessor.toProperties("postgres://upimesh:s3cret@dpg-abc123-a/upimesh_db");
        assertEquals("jdbc:postgresql://dpg-abc123-a:5432/upimesh_db", p.get("spring.datasource.url"));
        assertEquals("upimesh", p.get("spring.datasource.username"));
        assertEquals("s3cret", p.get("spring.datasource.password"));
        assertEquals("update", p.get("spring.jpa.hibernate.ddl-auto"), "a real database must keep its tables between restarts");
        assertEquals("SET lock_timeout = 10000", p.get("spring.datasource.hikari.connection-init-sql"));
    }

    @Test
    void explicitPortQueryAndPercentEncodedCredentialsAreHonoured() {
        Map<String, Object> p = DatabaseUrlPostProcessor.toProperties("postgresql://u%40x:p%2Fw%3A@db.example.com:6543/shop?sslmode=require");
        assertEquals("jdbc:postgresql://db.example.com:6543/shop?sslmode=require", p.get("spring.datasource.url"));
        assertEquals("u@x", p.get("spring.datasource.username"));
        assertEquals("p/w:", p.get("spring.datasource.password"));
    }

    @Test
    void aJdbcUrlPassesThroughUntouched() {
        Map<String, Object> p = DatabaseUrlPostProcessor.toProperties("jdbc:postgresql://h:5432/d?user=a&password=b");
        assertEquals("jdbc:postgresql://h:5432/d?user=a&password=b", p.get("spring.datasource.url"));
        assertFalse(p.containsKey("spring.datasource.username"));
    }

    @Test
    void otherDatabasesAndBrokenUrlsAreRejectedWithoutRevealingTheSecret() {
        IllegalArgumentException wrongScheme = assertThrows(IllegalArgumentException.class,
                () -> DatabaseUrlPostProcessor.toProperties("mysql://u:topsecret@h/d"));
        assertFalse(wrongScheme.getMessage().contains("topsecret"));
        IllegalArgumentException noDb = assertThrows(IllegalArgumentException.class,
                () -> DatabaseUrlPostProcessor.toProperties("postgres://u:topsecret@h:5432"));
        assertFalse(noDb.getMessage().contains("topsecret"));
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> DatabaseUrlPostProcessor.toProperties("postgres://u:top secret@h/d"));
        assertFalse(broken.getMessage().contains("top secret"));
    }

    @Test
    void theEnvironmentIsOnlyChangedWhenDatabaseUrlIsSet() {
        MockEnvironment plain = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:x");
        new DatabaseUrlPostProcessor().postProcessEnvironment(plain, null);
        assertEquals("jdbc:h2:mem:x", plain.getProperty("spring.datasource.url"));
        assertNull(plain.getProperty("spring.jpa.hibernate.ddl-auto"));

        MockEnvironment withUrl = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:x")
                .withProperty("DATABASE_URL", "postgres://a:b@h/d");
        new DatabaseUrlPostProcessor().postProcessEnvironment(withUrl, null);
        assertEquals("jdbc:postgresql://h:5432/d", withUrl.getProperty("spring.datasource.url"));
        assertEquals("update", withUrl.getProperty("spring.jpa.hibernate.ddl-auto"));
    }
}
