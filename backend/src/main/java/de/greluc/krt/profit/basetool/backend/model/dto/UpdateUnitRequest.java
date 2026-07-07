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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Inbound request payload for the Update Unit operation — the versioned twin of {@link
 * AddUnitRequest}.
 *
 * <p>It carries the same editable fields as {@link AddUnitRequest} plus {@code version}: the {@code
 * MissionUnit.@Version} the client last saw, echoed back so a stale full-form save is rejected with
 * a 409 instead of silently clobbering a concurrent edit (#1131). Because the whole update path
 * rewrites <em>every</em> unit field from the caller's form snapshot, the entity's own
 * {@code @Version} WHERE-clause never fires on a stale form (the fresh {@code findById} always
 * matches the current row version) — the client-echoed {@code version} is the only guard against
 * the lost update. {@code version} is nullable so a privileged force-save (or a legacy caller that
 * omits it) skips the check via {@link
 * de.greluc.krt.profit.basetool.backend.support.OptimisticLock#checkOptionalClient}; a present,
 * mismatching value 409s.
 *
 * <p>Field semantics match {@link AddUnitRequest}: {@code name} is the required display name;
 * {@code shipTypeId} / {@code shipId} are optional; {@code responsibleUserId} optionally pins the
 * responsible person; {@code note} is a free-text planning note.
 */
public record UpdateUnitRequest(
    @NotBlank @Size(max = 255) String name,
    UUID shipTypeId,
    UUID shipId,
    Boolean highValueUnit,
    Double frequency,
    UUID responsibleUserId,
    @Size(max = 500) String note,
    Long version) {
  /**
   * Null-safe accessor for the HVU flag.
   *
   * @return {@code true} only when the caller explicitly flagged the unit as high-value
   */
  public boolean isHighValueUnit() {
    return highValueUnit != null && highValueUnit;
  }
}
