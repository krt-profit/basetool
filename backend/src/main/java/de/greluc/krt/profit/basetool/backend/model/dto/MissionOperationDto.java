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
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The operation a mission DTO embeds, owned by the mission module so that it does not depend on the
 * operation module's DTO. It serializes with the field names and order of the operation endpoint's
 * DTO, and the OpenAPI document refers to that DTO's schema ({@code OperationDto}) from the mission
 * DTOs' {@code operation} component.
 *
 * @param id the operation
 * @param name the operation's name
 * @param description the operation's description, or {@code null}
 * @param status the name of the operation's lifecycle status
 * @param owningSquadron the owning org unit, or {@code null} for an ownerless leadership operation
 * @param version the operation's row version
 * @param createdAt the creation timestamp
 * @param updatedAt the last-update timestamp
 * @param payoutPreliminary always {@code null}: a mission does not know the operation's payout
 *     state
 */
public record MissionOperationDto(
    UUID id,
    String name,
    String description,
    String status,
    SquadronReferenceDto owningSquadron,
    Long version,
    Instant createdAt,
    Instant updatedAt,
    Boolean payoutPreliminary) {

  /**
   * Builds the embedded operation from the summary the operation module provides.
   *
   * @param summary the summary of the operation a mission belongs to
   * @return the embedded operation, with the payout flag unknown
   */
  @NotNull
  public static MissionOperationDto of(@NotNull MissionOperationSummary summary) {
    return new MissionOperationDto(
        summary.id(),
        summary.name(),
        summary.description(),
        summary.status(),
        owningSquadron(summary),
        summary.version(),
        summary.createdAt(),
        summary.updatedAt(),
        null);
  }

  @Nullable
  private static SquadronReferenceDto owningSquadron(MissionOperationSummary summary) {
    if (summary.owningOrgUnitId() == null) {
      return null;
    }
    return new SquadronReferenceDto(
        summary.owningOrgUnitId(), summary.owningOrgUnitName(), summary.owningOrgUnitShorthand());
  }
}
