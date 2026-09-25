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

import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Job order of either kind: a {@code MATERIAL} order fills {@code materials}, an {@code ITEM} order
 * fills {@code items} and {@code aggregatedMaterials}; the unused lists are empty.
 *
 * @param id job order primary key
 * @param displayId human-readable sequential id
 * @param responsibleOrgUnit processing org unit (slim reference)
 * @param requestingOrgUnit customer org unit the order is placed for (slim reference)
 * @param handle contact handle
 * @param comment optional free-text note
 * @param priority queue priority ({@code null} when terminal)
 * @param status lifecycle status
 * @param type order kind ({@code MATERIAL} or {@code ITEM})
 * @param countBlueprintsWithVariants whether the blueprint-coverage view counts cosmetic variants
 *     ({@code ITEM} orders only)
 * @param materials material lines ({@code MATERIAL} orders)
 * @param items ordered finished-item lines ({@code ITEM} orders)
 * @param aggregatedMaterials derived material requirements grouped by material and quality ({@code
 *     ITEM} orders)
 * @param assignees assignees, each with note and edge version
 * @param handovers material-handover events ({@code MATERIAL} orders)
 * @param itemHandovers item-handover events ({@code ITEM} orders)
 * @param createdAt creation instant (UTC)
 * @param version optimistic-lock version
 * @param redacted {@code true} when this is the requesting-owner view with processing-side data
 *     stripped (REQ-ORDERS-023)
 */
public record JobOrderDto(
    UUID id,
    Integer displayId,
    SquadronReferenceDto responsibleOrgUnit,
    SquadronReferenceDto requestingOrgUnit,
    String handle,
    String comment,
    Integer priority,
    JobOrderStatus status,
    JobOrderType type,
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
    boolean redacted) {

  /**
   * Returns a copy of this DTO with the assignee rows replaced.
   *
   * @param value the replacement assignee rows
   * @return a copy differing only in {@code assignees}
   */
  @NotNull
  public JobOrderDto withAssignees(List<JobOrderAssigneeDto> value) {
    return new JobOrderDto(
        id,
        displayId,
        responsibleOrgUnit,
        requestingOrgUnit,
        handle,
        comment,
        priority,
        status,
        type,
        countBlueprintsWithVariants,
        materials,
        items,
        aggregatedMaterials,
        value,
        handovers,
        itemHandovers,
        createdAt,
        version,
        canEdit,
        redacted);
  }

  /**
   * Returns a copy of this DTO with the {@link #redacted()} flag replaced.
   *
   * @param value the new {@code redacted} flag
   * @return a copy differing only in {@code redacted}
   */
  @NotNull
  public JobOrderDto withRedacted(boolean value) {
    return new JobOrderDto(
        id,
        displayId,
        responsibleOrgUnit,
        requestingOrgUnit,
        handle,
        comment,
        priority,
        status,
        type,
        countBlueprintsWithVariants,
        materials,
        items,
        aggregatedMaterials,
        assignees,
        handovers,
        itemHandovers,
        createdAt,
        version,
        canEdit,
        value);
  }
}
