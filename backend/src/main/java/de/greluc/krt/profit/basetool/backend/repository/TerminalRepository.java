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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.Terminal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for Terminal. */
public interface TerminalRepository extends JpaRepository<Terminal, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code IdTerminal}. */
  Optional<Terminal> findByIdTerminal(Integer id);

  /** Derived Spring-Data query - returns entities matching {@code Name}. */
  Optional<Terminal> findByName(String name);

  /**
   * Returns every currently reachable terminal of the given UEX kind. Called once per sweep with
   * {@link Terminal#TYPE_REFINERY} to recompute the derived {@code has_refinery_terminal} flags on
   * cities and space stations (REQ-REFINERY-020); the {@code is_available_live} filter is what
   * drops a decommissioned refinery out of the picker on the next sweep.
   *
   * @param type UEX terminal kind, e.g. {@link Terminal#TYPE_REFINERY}
   * @return matching live terminals, never {@code null}
   */
  List<Terminal> findByTypeAndIsAvailableLiveTrue(String type);
}
