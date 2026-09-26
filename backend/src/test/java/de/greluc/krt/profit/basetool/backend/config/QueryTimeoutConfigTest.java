/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the global statement timeout (REQ-DATA-009) cancels a long-running query against real
 * Postgres. Overrides the timeout to 1 s on the same property path production uses, so a passing
 * test also proves the production binding reaches Hibernate.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.jakarta.persistence.query.timeout=1000")
@Transactional
class QueryTimeoutConfigTest {

  @PersistenceContext private EntityManager entityManager;

  @Autowired private EntityManagerFactory entityManagerFactory;

  /**
   * The configured timeout must be present in the EntityManagerFactory's property map under the
   * standard JPA key — proving the {@code spring.jpa.properties.*} YAML binding reaches Hibernate.
   */
  @Test
  void queryTimeoutProperty_isBoundOnTheEntityManagerFactory() {
    assertThat(entityManagerFactory.getProperties())
        .containsEntry("jakarta.persistence.query.timeout", "1000");
  }

  /**
   * A query that runs longer than the configured timeout is cancelled by the database (Postgres
   * "canceling statement due to user request") and surfaces as an exception, instead of running to
   * completion and pinning the connection.
   */
  @Test
  void queryExceedingTheTimeout_isCancelled() {
    Throwable thrown =
        catchThrowable(
            () -> entityManager.createNativeQuery("SELECT pg_sleep(10)").getSingleResult());

    assertThat(thrown)
        .as("a 10s sleep under a 1s query timeout must be cancelled, not run to completion")
        .isNotNull();
    assertThat(thrown).hasStackTraceContaining("canceling statement");
  }

  /** A trivial query well under the timeout completes normally — the timeout is not over-eager. */
  @Test
  void fastQueryUnderTheTimeout_succeeds() {
    Object result = entityManager.createNativeQuery("SELECT 1").getSingleResult();

    assertThat(((Number) result).intValue()).isEqualTo(1);
  }
}
