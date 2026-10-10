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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderAllocationDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionAllocationDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend inventory-item projection. {@code createdAt} orders a stack's
 * entries oldest-first.
 *
 * <p>The quantity is split across job orders and missions ({@code *Allocations}, unassigned {@code
 * *Rest}; REQ-INV-027). A material row carries {@code material} and {@code quality}; a game-item
 * row carries {@code gameItem} with {@code null} material and quality (REQ-INV-029). {@code stolen}
 * is the „gestohlen" marker (REQ-INV-053).
 */
@DtoMirror
public record InventoryItemDto(
    UUID id,
    UserReferenceDto user,
    MaterialReferenceDto material,
    InventoryGameItemReferenceDto gameItem,
    LocationReferenceDto location,
    Integer quality,
    Double amount,
    Boolean personal,
    Boolean stolen,
    List<JobOrderAllocationDto> jobOrderAllocations,
    Double jobOrderRest,
    List<MissionAllocationDto> missionAllocations,
    Double missionRest,
    String note,
    SquadronReferenceDto owningSquadron,
    Long version,
    Boolean canEdit,
    Instant createdAt) {}
