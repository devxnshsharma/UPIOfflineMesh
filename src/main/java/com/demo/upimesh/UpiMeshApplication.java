package com.demo.upimesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * UPI Offline Mesh - mesh routed deferred payment settlement (software simulation).
 *
 * Run:  ./mvnw spring-boot:run   then open http://localhost:8080
 */
@SpringBootApplication
public class UpiMeshApplication {
    public static void main(String[] args) {
        SpringApplication.run(UpiMeshApplication.class, args);
    }
}
