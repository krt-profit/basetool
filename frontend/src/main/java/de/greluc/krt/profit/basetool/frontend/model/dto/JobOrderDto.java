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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderDto}. Serves both order kinds: {@code MATERIAL}
 * orders populate {@code materials} + {@code handovers}; {@code ITEM} orders populate {@code
 * items}, {@code aggregatedMaterials} and {@code itemHandovers}. The unused lists are empty for the
 * respective kind, so the detail UI renders both through one shape.
 *
 * <p>{@code redacted} mirrors the backend's per-order requesting-owner redaction signal
 * (REQ-ORDERS-023): {@code true} when the caller reached the order via the requesting-org-unit
 * escape rather than as a full viewer, so the detail page keys its limited rendering off THIS order
 * rather than the caller's global capability.
 */
public record JobOrderDto(
    UUID id,
    Integer displayId,
    SquadronReferenceDto responsibleOrgUnit,
    SquadronReferenceDto requestingOrgUnit,
    String handle,
    String comment,
    Integer priority,
    @BackendEnumAsString String status,
    @BackendEnumAsString String type,
    boolean countBlueprintsWithVariants,
    List<JobOrderMaterialDto> materials,
    List<JobOrderItemDto> items,
    List<AggregatedMaterialDto> aggregatedMaterials,
    List<JobOrderAssigneeDto> assignees,
    List<JobOrderHandoverDto> handovers,
    List<JobOrderItemHandoverDto> itemHandovers,
    Instant createdAt,
    Long version,
    boolean redacted) {}
