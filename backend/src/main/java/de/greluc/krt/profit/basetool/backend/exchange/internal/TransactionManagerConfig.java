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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces Spring Boot's JPA transaction manager with one that attributes every write to its source
 * for the change feed (ADR-0224).
 */
@Configuration(proxyBeanMethods = false)
public class TransactionManagerConfig {

  /**
   * The application's transaction manager.
   *
   * @param entityManagerFactory the persistence unit
   * @param changeSource names who is writing
   * @return the manager
   */
  @Bean
  public @NotNull PlatformTransactionManager transactionManager(
      @NotNull EntityManagerFactory entityManagerFactory, @NotNull ChangeSource changeSource) {
    return new ChangeSourceTransactionManager(entityManagerFactory, changeSource);
  }
}
