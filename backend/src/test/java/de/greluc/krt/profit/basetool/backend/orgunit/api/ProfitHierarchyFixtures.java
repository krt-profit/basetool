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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Constructs explicit synthetic Profit hierarchy graphs without granting caller authority. */
public final class ProfitHierarchyFixtures {

  private static final UUID IN_MEMORY_ROOT_ID =
      UUID.fromString("075e4458-0744-4113-9c78-73a0871b7bce");

  private ProfitHierarchyFixtures() {}

  /**
   * Constructs an active in-memory designated Profit area with a stable synthetic identity.
   *
   * @return an unpersisted Profit root
   */
  public static @NotNull Bereich profitArea() {
    Bereich root = new Bereich();
    root.setId(IN_MEMORY_ROOT_ID);
    root.setName("Fixture Profit Area");
    root.setShorthand("FXPRF");
    root.setDepartment(Department.PROFIT);
    return root;
  }

  /**
   * Parents one fixture unit to its explicit shared Profit area without changing activity or roles.
   *
   * @param unit the fixture Staffel or Spezialkommando
   * @param root the shared designated Profit parent
   */
  public static void withinProfit(@NotNull OrgUnit unit, @NotNull Bereich root) {
    unit.setParent(root);
  }

  /**
   * Reuses the sole designated Profit area or creates a synthetic one when none exists.
   *
   * @param repository the fixture's transactional org-unit repository
   * @return the unique active Profit root
   */
  public static @NotNull Bereich persistedProfitArea(@NotNull OrgUnitRepository repository) {
    List<Bereich> roots =
        repository.findAll().stream()
            .filter(Bereich.class::isInstance)
            .map(Bereich.class::cast)
            .filter(unit -> unit.getDepartment() == Department.PROFIT)
            .toList();
    if (roots.size() > 1) {
      throw new IllegalStateException("Fixture requires one designated Profit area");
    }
    if (!roots.isEmpty()) {
      Bereich root = roots.getFirst();
      if (!root.isActive()) {
        throw new IllegalStateException("Fixture Profit area must be active");
      }
      return root;
    }
    Bereich root = profitArea();
    root.setId(null);
    return repository.saveAndFlush(root);
  }
}
