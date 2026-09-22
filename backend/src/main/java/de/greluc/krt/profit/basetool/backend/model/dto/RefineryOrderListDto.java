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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Data transfer record carrying Refinery Order List payload. */
public record RefineryOrderListDto(
    UUID id,
    UserReferenceDto owner,
    LocationDto location,
    MissionReferenceDto mission,
    Instant startedAt,
    Long durationMinutes,
    Double expenses,
    Double otherExpenses,
    Double oreSales,
    Double profit,
    RefiningMethodDto refiningMethod,
    String status,
    List<RefineryGoodDto> goods,
    SquadronReferenceDto owningSquadron,
    Long version) {
  /**
   * Derived end timestamp ({@code startedAt + durationMinutes}); {@code null} if either is unset.
   */
  @Nullable
  public Instant getEndsAt() {
    if (startedAt != null && durationMinutes != null) {
      return startedAt.plus(durationMinutes, ChronoUnit.MINUTES);
    }
    return null;
  }
}
