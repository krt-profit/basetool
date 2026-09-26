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

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Viewer-relative projection of a {@link
 * de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer} for the Materialbörse board
 * and detail pane.
 *
 * <p>A {@link MaterialExchangeOfferKind#MATERIAL} offer fills {@link #material}, {@link #quality}
 * and {@link #amount}; an {@link MaterialExchangeOfferKind#ITEM} offer fills {@link #itemName} and
 * {@link #itemQuantity} (REQ-MARKET-012). {@link #availableAmount} and {@link #interestedHandles}
 * are only populated for the owner (REQ-MARKET-006).
 *
 * @param id the offer id.
 * @param kind whether this is a Lager-backed material offer or a blueprint-product item offer.
 * @param material the offered material for a material offer, else {@code null}.
 * @param itemName the offered item's display name for an item offer, else {@code null}.
 * @param itemQuantity the offered whole-piece quantity for an item offer, else {@code null}.
 * @param owner the offering player (the Anbieter).
 * @param ownerOrgUnits the Anbieter's badge-kind org units, Staffeln first, then Spezialkommandos,
 *     then Bereiche, each name-sorted; may be empty.
 * @param mine whether the requesting member owns this offer.
 * @param quality the offered quality (0–1000) for a material offer, else {@code null}.
 * @param amount the offered SCU quantity, clamped to current stock, for a material offer, else
 *     {@code null}.
 * @param availableAmount the item's current total SCU stock for the owner of a material offer,
 *     otherwise {@code null}.
 * @param releasedAt when the offer was last released.
 * @param remark the raw Markdown trade remark.
 * @param interestCount how many members have registered interest.
 * @param interestedHandles the interessenten handles for the owner, otherwise {@code null}.
 * @param viewerInterested whether the requesting member has registered interest on this offer.
 * @param status the offer's lifecycle status (ACTIVE / DEACTIVATED).
 * @param version the optimistic-lock version, echoed for the next edit.
 */
public record MaterialExchangeOfferDto(
    UUID id,
    MaterialExchangeOfferKind kind,
    MaterialReferenceDto material,
    String itemName,
    Integer itemQuantity,
    UserReferenceDto owner,
    List<OrgUnitReferenceDto> ownerOrgUnits,
    boolean mine,
    Integer quality,
    Double amount,
    Double availableAmount,
    Instant releasedAt,
    String remark,
    int interestCount,
    List<String> interestedHandles,
    boolean viewerInterested,
    MaterialExchangeOfferStatus status,
    Long version) {}
