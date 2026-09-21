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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code RefineryOrderDto} wire shape (per the {@code
 * feedback_backend_frontend_dto_mirror} memory: backend + frontend records must stay aligned
 * field-for-field, or a render-time 500 surfaces in prod).
 *
 * <p>The trailing {@code owningOrgUnitId} field is the R5.d picker output sent to the backend on
 * create; {@code null} preserves the legacy "owner's home Staffel" stamping path.
 */
public record RefineryOrderDto(
    UUID id,
    UserReferenceDto owner,
    LocationDto location,
    MissionReferenceDto mission,
    Instant startedAt,
    @Positive Long durationMinutes,
    @Positive Double expenses,
    Double otherExpenses,
    Double oreSales,
    Double profit,
    RefiningMethodDto refiningMethod,
    List<RefineryGoodDto> goods,
    RefineryOrderStatus status,
    SquadronReferenceDto owningSquadron,
    Long version,
    UUID owningOrgUnitId) {
  /**
   * Derived end timestamp ({@code startedAt + durationMinutes}); {@code null} if either is unset.
   */
  @Nullable
  public Instant getEndsAt() {
    if (startedAt != null && durationMinutes != null) {
      return startedAt.plus(durationMinutes, java.time.temporal.ChronoUnit.MINUTES);
    }
    return null;
  }
}
