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

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Viewer-relative projection of a {@link
 * de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequest} for the Materialbörse
 * Gesuche board and detail pane.
 *
 * <p>A {@link MaterialExchangeRequestKind#MATERIAL} request fills {@link #material} and {@link
 * #requestedAmount}; an {@link MaterialExchangeRequestKind#ITEM} request fills {@link #itemName}
 * and {@link #itemQuantity}. {@link #interestedHandles} is only populated for the owner
 * (REQ-MARKET-019).
 *
 * @param id the request id.
 * @param kind whether this is a catalogue-material request or a blueprint-product item request.
 * @param material the requested material for a material request, else {@code null}.
 * @param itemName the requested item's display name for an item request, else {@code null}.
 * @param itemQuantity the requested whole-piece quantity for an item request, else {@code null}.
 * @param requestedAmount the requested quantity in the material's own unit for a material request,
 *     else {@code null}.
 * @param minQuality the optional minimum desired quality (0–1000), or {@code null} for no floor.
 * @param owner the requesting player (the Suchende).
 * @param ownerOrgUnits the requester's badge-kind org units, Staffeln first, then Spezialkommandos,
 *     then Bereiche, each name-sorted; may be empty.
 * @param mine whether the requesting member owns this request.
 * @param postedAt when the request was last posted.
 * @param remark the raw Markdown description.
 * @param interestCount how many members have signalled they can supply it.
 * @param interestedHandles the supplier handles for the owner, otherwise {@code null}.
 * @param viewerInterested whether the requesting member has signalled they can supply this request.
 * @param status the request's lifecycle status (ACTIVE / DEACTIVATED).
 * @param version the optimistic-lock version, echoed for the next edit.
 */
public record MaterialRequestDto(
    UUID id,
    MaterialExchangeRequestKind kind,
    MaterialReferenceDto material,
    String itemName,
    Integer itemQuantity,
    Double requestedAmount,
    Integer minQuality,
    UserReferenceDto owner,
    List<OrgUnitReferenceDto> ownerOrgUnits,
    boolean mine,
    Instant postedAt,
    String remark,
    int interestCount,
    List<String> interestedHandles,
    boolean viewerInterested,
    MaterialExchangeRequestStatus status,
    Long version) {}
