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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload of a bulk org-unit change of the caller's ships (REQ-HANGAR-010). It carries no
 * counters: the ships are locked pessimistically.
 *
 * @param shipIds the selected ships; every one must belong to the caller
 * @param orgUnitId the new owning org unit, one of the caller's direct memberships, or {@code null}
 *     for none
 */
public record ShipBulkOrgUnitRequestDto(
    @NotNull @NotEmpty @Size(max = ShipBulkOrgUnitRequestDto.MAX_SHIPS) List<@NotNull UUID> shipIds,
    @Nullable UUID orgUnitId) {

  /** The largest selection one request may carry. */
  public static final int MAX_SHIPS = 1000;
}
