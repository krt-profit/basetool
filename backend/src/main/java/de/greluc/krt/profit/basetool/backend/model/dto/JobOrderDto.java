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

/**
 * Data transfer record carrying Job Order payload. The same record serves both order kinds (see
 * {@code type}): a {@code MATERIAL} order populates {@code materials}; an {@code ITEM} order
 * populates {@code items} (ordered finished items) and {@code aggregatedMaterials} (the internal
 * material requirements derived from the items, grouped by material + quality). The unused list is
 * empty for the respective kind, so the detail UI can render both with one shared shell.
 *
 * @param id job order primary key
 * @param displayId human-readable sequential id
 * @param responsibleOrgUnit processing org unit (slim reference); {@code null} only on pre-rework
 *     rows not yet backfilled (Phase 3)
 * @param requestingOrgUnit customer org unit the order is placed for (slim reference)
 * @param handle contact handle
 * @param comment optional free-text note
 * @param priority queue priority (null when terminal)
 * @param status lifecycle status
 * @param type order kind ({@code MATERIAL} or {@code ITEM})
 * @param countBlueprintsWithVariants whether the item-order blueprint-coverage view counts cosmetic
 *     variants of the ordered items toward availability ({@code true}, family matching) or matches
 *     blueprints exactly ({@code false}); relevant only to {@code ITEM} orders
 * @param materials material lines (populated for {@code MATERIAL} orders; empty for {@code ITEM})
 * @param items ordered finished-item lines (populated for {@code ITEM} orders; empty for {@code
 *     MATERIAL})
 * @param aggregatedMaterials derived material requirements grouped by material + quality (populated
 *     for {@code ITEM} orders; empty for {@code MATERIAL})
 * @param assignees assignees of the order, each carrying the user, their optional note and the
 *     assignee edge's own version
 * @param handovers material-handover events (populated for {@code MATERIAL} orders)
 * @param itemHandovers item-handover events (populated for {@code ITEM} orders)
 * @param createdAt creation instant (UTC)
 * @param version optimistic-lock version
 * @param redacted whether this DTO is the requesting-owner redacted view (REQ-ORDERS-023): {@code
 *     true} when the caller reached the order via the requesting-org-unit escape rather than as a
 *     full (responsible-side / admin) viewer, so the processing-side surfaces
 *     (Bearbeiter/aggregated/handovers, per-line collection progress) have been stripped. Lets a
 *     client key its rendering off THIS order's per-order signal instead of a global capability.
 *     {@code false} for the full view.
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
    boolean redacted) {

  /**
   * Returns a copy of this DTO with the assignee rows replaced and every other component unchanged.
   * Used by the read path to apply the shared peer projection to each assignee's nested {@code
   * UserDto}, which otherwise carries the full member record to every viewer of the order.
   *
   * @param value the replacement assignee rows
   * @return a copy differing only in {@code assignees}
   */
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
        redacted);
  }

  /**
   * Returns a copy of this DTO with the {@link #redacted()} flag overridden and every other
   * component unchanged. Used by the read path to stamp the per-order redaction decision onto an
   * otherwise-complete projection without re-listing all eighteen preceding fields at the call
   * site.
   *
   * @param value the new {@code redacted} flag
   * @return a copy differing only in {@code redacted}
   */
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
        value);
  }
}
