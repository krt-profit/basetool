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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What the Lager shows of a mission its stock is earmarked to.
 *
 * @param id the mission
 * @param name the mission's name, or {@code null} for a caller who may not read the mission
 * @param plannedStartTime the mission's planned start, or {@code null}
 */
public record MissionEarmark(
    @NotNull UUID id, @Nullable String name, @Nullable Instant plannedStartTime) {

  /**
   * Returns this earmark as a caller who may not read the mission is shown it (REQ-INV-027).
   *
   * @return the earmark with the mission's id alone
   */
  public @NotNull MissionEarmark withoutDetails() {
    return new MissionEarmark(id, null, null);
  }
}
