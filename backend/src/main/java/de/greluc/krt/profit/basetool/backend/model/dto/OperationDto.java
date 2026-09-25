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

import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying an operation.
 *
 * @param id operation primary key
 * @param name operation name
 * @param description optional free-text description
 * @param status current operation status
 * @param owningSquadron squadron that owns the operation (multi-tenant scope marker)
 * @param version optimistic-lock version
 * @param createdAt creation timestamp (UTC)
 * @param updatedAt last-update timestamp (UTC)
 * @param payoutPreliminary {@code true} when a mission of the operation lacks an actual start or
 *     end time, so the payout may still change; {@code null} (unknown) on every endpoint except the
 *     detail {@code GET}
 */
public record OperationDto(
    UUID id,
    String name,
    String description,
    OperationStatus status,
    SquadronReferenceDto owningSquadron,
    Long version,
    Instant createdAt,
    Instant updatedAt,
    Boolean payoutPreliminary) {

  /**
   * Returns a copy of this DTO with {@code payoutPreliminary} replaced.
   *
   * @param value the flag value for the copy
   * @return a new {@code OperationDto} differing only in {@code payoutPreliminary}
   */
  @NotNull
  public OperationDto withPayoutPreliminary(@Nullable Boolean value) {
    return new OperationDto(
        id, name, description, status, owningSquadron, version, createdAt, updatedAt, value);
  }
}
