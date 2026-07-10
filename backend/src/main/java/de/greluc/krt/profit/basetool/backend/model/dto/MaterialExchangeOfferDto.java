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
 * Viewer-relative read projection of a {@link
 * de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer} for the Materialbörse board
 * and detail pane (REQ-MARKET-001…). Assembled in {@code MaterialExchangeService} rather than by a
 * MapStruct mapper because {@link #mine}, {@link #foreign}, {@link #interestCount}, {@link
 * #viewerInterested} and {@link #interestedHandles} all depend on the requesting member.
 *
 * <p>The projection carries both offer kinds (REQ-MARKET-012), discriminated by {@link #kind}. For
 * a {@link MaterialExchangeOfferKind#MATERIAL} offer, {@link #material}, {@link #quality} and
 * {@link #amount} are populated (read live from the linked Lager item) and {@link #itemName}/{@link
 * #itemQuantity} are {@code null}. For an {@link MaterialExchangeOfferKind#ITEM} offer, {@link
 * #itemName} and {@link #itemQuantity} are populated and {@link #material}/{@link #quality}/{@link
 * #amount} are {@code null} (a craftable item has no quality). Either way there is deliberately
 * <b>no location/Standort field</b> and <b>no category field</b> (REQ-MARKET-004/005). The trade
 * {@link #remark} is the raw Markdown source — the frontend renders it through the sanitizing
 * {@code @markdown} bean into a {@code .markdown-content} block; it is never rendered client-side.
 *
 * <p><b>Anonymity (REQ-MARKET-006):</b> {@link #interestedHandles} is populated <b>only</b> when
 * the requesting member is the offer's owner; for every other viewer it is {@code null} and only
 * {@link #interestCount} is disclosed.
 *
 * @param id the offer id.
 * @param kind whether this is a Lager-backed material offer or a blueprint-product item offer.
 * @param material the offered material (id, name, quantity type) for a material offer, else {@code
 *     null} — never a category.
 * @param itemName the offered item's display name for an item offer, else {@code null}.
 * @param itemQuantity the offered whole-piece quantity for an item offer, else {@code null}.
 * @param owner the offering player (the Anbieter), shown to everyone as "von {Spieler}".
 * @param squadron the owner's squadron/org-unit badge, or {@code null} for an ownerless-personal
 *     offer.
 * @param foreign whether the offer's squadron differs from the viewer's (drives the foreign badge).
 * @param mine whether the requesting member owns this offer.
 * @param quality the offered quality (0–1000) for a material offer, else {@code null}.
 * @param amount the offered quantity in SCU for a material offer, else {@code null}.
 * @param releasedAt when the offer was (last) released — drives "Freigegeben vor X".
 * @param remark the raw Markdown trade remark ("was suchst du im Gegenzug?").
 * @param interestCount how many members have registered interest (the anonymity-safe figure).
 * @param interestedHandles the interessenten handles — only for the owner, otherwise {@code null}.
 * @param viewerInterested whether the requesting member has registered interest on this offer.
 * @param status the offer's lifecycle status (ACTIVE / DEACTIVATED).
 * @param version the optimistic-lock version, echoed for the next remark edit.
 */
public record MaterialExchangeOfferDto(
    UUID id,
    MaterialExchangeOfferKind kind,
    MaterialReferenceDto material,
    String itemName,
    Integer itemQuantity,
    UserReferenceDto owner,
    SquadronReferenceDto squadron,
    boolean foreign,
    boolean mine,
    Integer quality,
    Double amount,
    Instant releasedAt,
    String remark,
    int interestCount,
    List<String> interestedHandles,
    boolean viewerInterested,
    MaterialExchangeOfferStatus status,
    Long version) {}
