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
 * Data transfer record carrying Create / Update Job Order payload.
 *
 * <p>Two org-unit references:
 *
 * <ul>
 *   <li>{@code responsibleOrgUnitId} — the org unit that <em>processes</em> the order. Required,
 *       and must be a profit-eligible squadron or Spezialkommando; the service returns 400
 *       otherwise. Creating an order requires a login (ADR-0149), so there is one rule rather than
 *       the two this field used to carry — the forgiving guest path that routed an anonymous
 *       request onto a configured intake Spezialkommando went with the public request form. Ignored
 *       on update — the responsible org unit is only changed through the dedicated reassignment
 *       endpoint ({@code PATCH /api/v1/orders/{id}/responsible-org-unit}).
 *   <li>{@code requestingOrgUnitId} — the org unit the order is placed on behalf of (the customer).
 *       Any squadron or Spezialkommando, no profit-eligibility restriction. Mandatory; the service
 *       returns 400 when it does not resolve.
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
