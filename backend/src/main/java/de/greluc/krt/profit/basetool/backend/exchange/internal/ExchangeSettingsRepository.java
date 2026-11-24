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

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access to the exchange switches and the registry's revision counter (REQ-XCH-003, REQ-BXCH-006,
 * REQ-OXCH-009).
 */
public interface ExchangeSettingsRepository extends JpaRepository<ExchangeSettings, Short> {

  /**
   * Loads the settings row under a row lock; every registry change takes it first, so changes are
   * serialised and each sees the one before it.
   *
   * @param id the singleton id
   * @return the locked row, or empty when the migration did not run
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM ExchangeSettings s WHERE s.id = :id")
  Optional<ExchangeSettings> findByIdForUpdate(@Param("id") Short id);

  /**
   * Writes the bank switch alone: its state, its own version counter and its change time, leaving
   * the row's version and {@code updated_at} to the global switch (REQ-BXCH-006).
   *
   * @param id the singleton id
   * @param enabled the bank switch's new state
   * @param at the change time
   * @return the number of rows written, one
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "UPDATE exchange_settings SET bank_enabled = :enabled,"
              + " bank_version = bank_version + 1, bank_updated_at = :at WHERE id = :id",
      nativeQuery = true)
  int writeBankSwitch(
      @Param("id") Short id, @Param("enabled") boolean enabled, @Param("at") Instant at);

  /**
   * Writes the job-order family switch alone: its state, its own version counter and its change
   * time, leaving every other switch's version and time alone (REQ-OXCH-009).
   *
   * @param id the singleton id
   * @param enabled the family switch's new state
   * @param at the change time
   * @return the number of rows written, one
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "UPDATE exchange_settings SET job_orders_enabled = :enabled,"
              + " job_orders_version = job_orders_version + 1, job_orders_updated_at = :at"
              + " WHERE id = :id",
      nativeQuery = true)
  int writeJobOrdersSwitch(
      @Param("id") Short id, @Param("enabled") boolean enabled, @Param("at") Instant at);

  /**
   * Writes the job-order writes switch alone: its state, its own version counter and its change
   * time, leaving every other switch's version and time alone (REQ-OXCH-009).
   *
   * @param id the singleton id
   * @param enabled the writes switch's new state
   * @param at the change time
   * @return the number of rows written, one
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "UPDATE exchange_settings SET job_order_writes_enabled = :enabled,"
              + " job_order_writes_version = job_order_writes_version + 1,"
              + " job_order_writes_updated_at = :at WHERE id = :id",
      nativeQuery = true)
  int writeJobOrderWritesSwitch(
      @Param("id") Short id, @Param("enabled") boolean enabled, @Param("at") Instant at);

  /**
   * Draws the next mirror revision.
   *
   * @return a number greater than every revision drawn before
   */
  @Query(value = "SELECT nextval('exchange_registry_revision_seq')", nativeQuery = true)
  long nextRevision();
}
