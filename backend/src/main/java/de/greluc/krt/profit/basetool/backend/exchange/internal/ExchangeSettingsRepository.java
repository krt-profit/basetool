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
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the global exchange switch and the registry's revision counter (REQ-XCH-003). */
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
   * Draws the next mirror revision.
   *
   * @return a number greater than every revision drawn before
   */
  @Query(value = "SELECT nextval('exchange_registry_revision_seq')", nativeQuery = true)
  long nextRevision();
}
