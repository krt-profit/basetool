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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.PauseReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Where purchasing is paused (REQ-PUR-019): for the whole organisation and for single raising
 * units.
 *
 * @param globalPaused whether purchasing is paused for the whole organisation
 * @param globalReason why, or {@code null} while running
 * @param globalPausedAt since when, or {@code null} while running
 * @param units the raising units whose needs may not be bought
 * @param mayManageGlobal whether the caller can pause or resume the whole organisation
 * @param pausableUnits the active raising units the caller may pause
 */
public record PauseStateDto(
    boolean globalPaused,
    @Nullable PauseReason globalReason,
    @Nullable Instant globalPausedAt,
    List<UnitPause> units,
    boolean mayManageGlobal,
    List<PausableUnit> pausableUnits) {

  /**
   * An active raising unit that the caller may pause.
   *
   * @param orgUnitId the unit
   * @param orgUnitName the unit's name
   */
  public record PausableUnit(UUID orgUnitId, String orgUnitName) {}

  /**
   * One paused raising unit.
   *
   * @param orgUnitId the unit
   * @param orgUnitName the unit's name
   * @param reason why it is paused
   * @param pausedAt since when
   * @param mayResume whether the caller may resume purchasing for the unit
   */
  public record UnitPause(
      UUID orgUnitId,
      String orgUnitName,
      PauseReason reason,
      Instant pausedAt,
      boolean mayResume) {}
}
