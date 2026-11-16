package com.finsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FinSight AI — Modular Monolith entry point.
 *
 * <p>Module packages under {@code com.finsight}:
 * <ul>
 *   <li>auth</li>
 *   <li>accounts</li>
 *   <li>transactions</li>
 *   <li>budgeting</li>
 *   <li>analytics</li>
 *   <li>cashflow</li>
 *   <li>fraud</li>
 *   <li>aiinsights (post-MVP)</li>
 *   <li>reporting (post-MVP)</li>
 *   <li>shared — cross-cutting utilities</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class FinSightApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinSightApplication.class, args);
    }
}
