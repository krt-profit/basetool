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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Create / update payload for a material job order.
 *
 * <ul>
 *   <li>{@code responsibleOrgUnitId} — the processing org unit; required and profit-eligible,
 *       otherwise 400. Ignored on update; changed only via {@code PATCH
 *       /api/v1/orders/{id}/responsible-org-unit}.
 *   <li>{@code requestingOrgUnitId} — the customer org unit; any squadron or Spezialkommando;
 *       mandatory.
 *   <li>{@code comment} — optional free-text note (≤1000 chars), HTML-escaped on display.
 * </ul>
 */
public record CreateJobOrderDto(
    @Nullable UUID responsibleOrgUnitId,
    @Nullable UUID requestingOrgUnitId,
    @Size(max = 200) String handle,
    @Size(max = 1000) String comment,
    @NotEmpty @Size(max = 50) List<@Valid CreateJobOrderMaterialDto> materials,
    Long version) {}
