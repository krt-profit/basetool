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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Persists the org units integration tests place orders and memberships on. */
public final class OrgUnitFixtures {

  private OrgUnitFixtures() {}

  /**
   * Saves and flushes a Staffel whose name and shorthand are both {@code name}.
   *
   * @param squadrons the repository the Staffel is saved through
   * @param name the name and shorthand, unique among the org units of the database
   * @param parent the parent unit, or {@code null} for a Staffel without a parent
   * @return the persisted Staffel
   */
  @NotNull
  public static Squadron persistedSquadron(
      @NotNull SquadronRepository squadrons, @NotNull String name, @Nullable OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return squadrons.saveAndFlush(unit);
  }
}
