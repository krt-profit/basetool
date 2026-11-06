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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Supplies the transaction-scoped PostgreSQL stock-lot lock protocol. */
@NoRepositoryBean
interface InventoryLotLockRepository extends Repository<InventoryItem, UUID> {
  /**
   * Waits for a lot lock that is released on transaction commit or rollback.
   *
   * @param key stock-lot lock key
   * @return always one
   */
  @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
  int lockExchangeLot(@Param("key") long key);

  /**
   * Attempts a lot lock without waiting while a catalogue foreign-key fence is held.
   *
   * @param key stock-lot lock key
   * @return whether the transaction holds the lock
   */
  @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
  boolean tryLockExchangeLot(@Param("key") long key);
}
