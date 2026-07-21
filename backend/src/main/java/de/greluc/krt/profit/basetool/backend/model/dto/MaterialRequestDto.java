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
 * Viewer-relative read projection of a {@link
 * de.greluc.krt.profit.basetool.backend.model.MaterialExchangeRequest} for the Materialbörse
 * Gesuche board and detail pane (REQ-MARKET-015…). Assembled in {@code MaterialRequestBoardService}
 * rather than by a MapStruct mapper because {@link #mine}, {@link #interestCount}, {@link
 * #viewerInterested} and {@link #interestedHandles} all depend on the requesting member.
 *
 * <p>The projection carries both request kinds, discriminated by {@link #kind}. For a {@link
 * MaterialExchangeRequestKind#MATERIAL} request, {@link #material} and {@link #requestedAmount} are
 * populated and {@link #itemName}/{@link #itemQuantity} are {@code null}; for an {@link
 * MaterialExchangeRequestKind#ITEM} request it is the reverse. Either kind may carry an optional
 * {@link #minQuality} (the requester's desired floor). There is deliberately no location field. The
 * {@link #remark} is the raw Markdown source — the frontend renders it through the sanitizing
 * {@code @markdown} bean into a {@code .markdown-content} block; it is never rendered client-side.
 *
 * <p><b>Anonymity (REQ-MARKET-019):</b> {@link #interestedHandles} is populated <b>only</b> when
 * the requesting member is the request's owner; for every other viewer it is {@code null} and only
 * {@link #interestCount} is disclosed.
 *
 * @param id the request id.
 * @param kind whether this is a catalogue-material request or a blueprint-product item request.
 * @param material the requested material (id, name, quantity type) for a material request, else
 *     {@code null}.
 * @param itemName the requested item's display name for an item request, else {@code null}.
 * @param itemQuantity the requested whole-piece quantity for an item request, else {@code null}.
 * @param requestedAmount the requested quantity in the material's own unit for a material request,
 *     else {@code null}.
 * @param minQuality the optional minimum desired quality (0–1000), or {@code null} for no floor.
 * @param owner the requesting player (the Suchende), shown to everyone as "gesucht von {Spieler}".
 * @param ownerOrgUnits every badge-kind org unit the requester currently belongs to — Staffel(n)
 *     first, then Spezialkommando(s), then Bereich(e), each name-sorted; empty when they have none.
 * @param mine whether the requesting member owns this request.
 * @param postedAt when the request was (last) posted — drives "Gesucht vor X".
 * @param remark the raw Markdown description.
 * @param interestCount how many members have signalled they can supply it (the anonymity-safe
 *     figure).
 * @param interestedHandles the supplier handles — only for the owner, otherwise {@code null}.
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
