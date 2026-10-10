package com.demo.upimesh.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that destroys shared state or costs real CPU (reset, simulation settings, experiments).
 * Locally it is open. On a hosted deployment ({@code upi.mesh.security.hosted=true}) {@link AdminInterceptor}
 * lets it through only with the admin token, and refuses it outright when no token is configured.
 * Protecting the handler instead of a URL means no spelling of the path can slip past the check.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdminOnly {
}
