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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/** Planning rows shared by mission template reads and writes. */
public final class MissionTemplatePlan {

  private MissionTemplatePlan() {}

  /**
   * A classified goal in display order.
   *
   * @param title the goal text
   * @param kind the goal classification
   */
  @DtoMirror
  public record Objective(String title, MissionObjectiveKind kind) {}

  /**
   * A procedure step in execution order.
   *
   * @param title the step text
   * @param meta the optional time or place hint
   */
  @DtoMirror
  public record Step(String title, String meta) {}

  /**
   * A reusable unit slot.
   *
   * @param name the display name
   * @param shipTypeId the optional catalogue ship type
   * @param note the optional unit note
   * @param highValueUnit whether the unit needs extra protection
   * @param frequency the optional frequency in MHz
   */
  @DtoMirror
  public record Unit(
      String name, UUID shipTypeId, String note, boolean highValueUnit, Double frequency) {}
}
