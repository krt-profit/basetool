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
 * Frontend view of a Materialbörse request (Gesuch), deserialized from the backend {@code
 * /api/v1/material-requests} responses. Mirrors the backend DTO field-for-field; {@link #kind} and
 * {@link #status} are decoded from the backend enum names via {@link BackendEnumAsString}. Carries
 * the raw {@link #remark} Markdown, which the template renders server-side through the {@code
 * @markdown} bean into a {@code .markdown-content} block. {@link #interestedHandles} is
 * {@code null} for a non-owner viewer (anonymity, REQ-MARKET-019).
 *
 * @param id the request id.
 * @param kind the request kind name (MATERIAL / ITEM); drives which branch is rendered.
 * @param material the requested material reference for a material request, else {@code null}.
 * @param itemName the requested item's display name for an item request, else {@code null}.
 * @param itemQuantity the requested whole-piece quantity for an item request, else {@code null}.
 * @param requestedAmount the requested quantity in the material's own unit for a material request,
 *     else {@code null}.
 * @param minQuality the optional minimum desired quality (0–1000), or {@code null} for no floor.
 * @param owner the requesting player (the Suchende).
 * @param ownerOrgUnits every badge-kind org unit the requester belongs to, rendered as affiliation
 *     badges after the username; empty when they have none.
 * @param mine whether the viewer owns this request.
 * @param postedAt when the request was posted.
 * @param remark the raw Markdown description.
 * @param interestCount how many members signalled they can supply it.
 * @param interestedHandles the supplier handles — only for the owner, otherwise {@code null}.
 * @param viewerInterested whether the viewer has signalled they can supply it.
 * @param status the request status name (ACTIVE / DEACTIVATED).
 * @param version the optimistic-lock version.
 */
public record MaterialRequestDto(
    UUID id,
    @BackendEnumAsString String kind,
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
    @BackendEnumAsString String status,
    Long version) {}
