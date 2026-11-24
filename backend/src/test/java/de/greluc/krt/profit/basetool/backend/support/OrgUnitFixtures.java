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

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Builds the Staffeln unit tests hand around and persists the ones integration tests use. */
public final class OrgUnitFixtures {

  private OrgUnitFixtures() {}

  /**
   * Builds an unsaved Staffel that carries an id only.
   *
   * @param id the id
   * @return the Staffel, without name, shorthand or parent
   */
  @NotNull
  public static Squadron squadron(@NotNull UUID id) {
    return squadron(id, null, null);
  }

  /**
   * Builds an unsaved Staffel with an id and a name.
   *
   * @param id the id
   * @param name the name
   * @return the Staffel, without shorthand or parent
   */
  @NotNull
  public static Squadron squadron(@NotNull UUID id, @Nullable String name) {
    return squadron(id, name, null);
  }

  /**
   * Builds an unsaved Staffel with an id, a name and a shorthand.
   *
   * @param id the id
   * @param name the name
   * @param shorthand the shorthand
   * @return the Staffel, without parent
   */
  @NotNull
  public static Squadron squadron(
      @NotNull UUID id, @Nullable String name, @Nullable String shorthand) {
    Squadron unit = new Squadron();
    unit.setId(id);
    unit.setName(name);
    unit.setShorthand(shorthand);
    return unit;
  }

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
    return persistedSquadron(squadrons, name, name, parent);
  }

  /**
   * Saves and flushes a Staffel.
   *
   * @param squadrons the repository the Staffel is saved through
   * @param name the name
   * @param shorthand the shorthand, unique among the org units of the database
   * @param parent the parent unit, or {@code null} for a Staffel without a parent
   * @return the persisted Staffel
   */
  @NotNull
  public static Squadron persistedSquadron(
      @NotNull SquadronRepository squadrons,
      @NotNull String name,
      @NotNull String shorthand,
      @Nullable OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(shorthand);
    unit.setParent(parent);
    return squadrons.saveAndFlush(unit);
  }

  /**
   * Saves and flushes a Staffel below the shared Profit area, creating the area when there is none.
   *
   * @param squadrons the repository the Staffel is saved through
   * @param orgUnits the repository the Profit area is found or saved through
   * @param name the name
   * @param shorthand the shorthand, unique among the org units of the database
   * @return the persisted Staffel
   */
  @NotNull
  public static Squadron persistedProfitSquadron(
      @NotNull SquadronRepository squadrons,
      @NotNull OrgUnitRepository orgUnits,
      @NotNull String name,
      @NotNull String shorthand) {
    return persistedSquadron(squadrons, name, shorthand, persistedProfitArea(orgUnits));
  }
}
