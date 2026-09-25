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
 * Frontend mirror of the backend {@code JobOrderDto} for both order kinds: {@code MATERIAL} orders
 * fill {@code materials} and {@code handovers}; {@code ITEM} orders fill {@code items}, {@code
 * aggregatedMaterials} and {@code itemHandovers}. The other kind's lists are empty.
 *
 * <p>{@code redacted} is {@code true} when the caller sees the order only as a member of the
 * requesting org unit (REQ-ORDERS-023).
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
    Boolean canEdit,
    boolean redacted) {}
