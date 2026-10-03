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

package de.greluc.krt.profit.basetool.architecture.fixtures.bank;

import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.Repository;

/** Planted violation: a ledger repository with a modifying query and a delete method. */
public interface FixtureLedgerRepository extends Repository<LedgerFixtureService, UUID> {

  /** Rewrites ledger rows. */
  @Modifying
  void wipe();

  /** Deletes every ledger row. */
  void deleteAll();
}
