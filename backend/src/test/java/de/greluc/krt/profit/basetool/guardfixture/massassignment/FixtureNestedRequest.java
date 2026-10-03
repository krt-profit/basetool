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

package de.greluc.krt.profit.basetool.guardfixture.massassignment;

import java.util.List;
import java.util.UUID;

/**
 * A request whose nested line carries a server-managed org unit; a nested {@code id} is allowed.
 *
 * @param name a plain client field
 * @param lines the nested lines
 */
public record FixtureNestedRequest(String name, List<Line> lines) {

  /**
   * One nested line.
   *
   * @param id the referenced row, allowed on a nested type
   * @param owningOrgUnitId a server-managed org unit the rule must notice
   */
  public record Line(UUID id, UUID owningOrgUnitId) {}
}
