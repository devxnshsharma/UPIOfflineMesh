package com.demo.upimesh.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets a managed PostgreSQL be configured with the single DATABASE_URL variable that hosting platforms provide
 * ({@code postgres://user:password@host:5432/name}). Without it the application keeps its in-memory H2 database.
 * With it the schema is kept between restarts (ddl-auto=update instead of create-drop), so data survives.
 */
public class DatabaseUrlPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String raw = env.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank()) return;
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", toProperties(raw.trim())));
    }

    /** Public so the parsing rules are unit tested. The URL itself is never put in an error message: it carries a password. */
    public static Map<String, Object> toProperties(String raw) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (raw.startsWith("jdbc:")) {
            p.put("spring.datasource.url", raw);
        } else {
            URI uri;
            try {
                uri = URI.create(raw);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("DATABASE_URL is not a valid URL (special characters in the password must be percent-encoded)");
            }
            if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
                throw new IllegalArgumentException("DATABASE_URL must start with postgres://, postgresql:// or jdbc:");
            }
            if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
                throw new IllegalArgumentException("DATABASE_URL needs a host and a database name: postgres://user:password@host:5432/name");
            }
            p.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort())
                    + uri.getPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            String userInfo = uri.getRawUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                p.put("spring.datasource.username", decode(colon < 0 ? userInfo : userInfo.substring(0, colon)));
                if (colon >= 0) p.put("spring.datasource.password", decode(userInfo.substring(colon + 1)));
            }
        }
        p.put("spring.jpa.hibernate.ddl-auto", "update");
        p.put("spring.datasource.hikari.maximum-pool-size", "5");
        p.put("spring.datasource.hikari.connection-init-sql", "SET lock_timeout = 10000"); // the H2 URL's LOCK_TIMEOUT, for PostgreSQL
        return p;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
