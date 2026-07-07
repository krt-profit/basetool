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
 * Frontend view of a Materialbörse offer, deserialized from the backend {@code
 * /api/v1/material-exchange} responses. Mirrors the backend DTO field-for-field; {@link #status} is
 * decoded from the backend enum name via {@link BackendEnumAsString}. Carries the raw {@link
 * #remark} Markdown, which the template renders server-side through the {@code @markdown} bean into
 * a {@code .markdown-content} block. {@link #interestedHandles} is {@code null} for a non-owner
 * viewer (anonymity, REQ-MARKET-006).
 *
 * @param id the offer id.
 * @param material the offered material reference.
 * @param owner the offering player (the Anbieter).
 * @param squadron the owner's squadron badge, or {@code null}.
 * @param foreign whether the offer's squadron differs from the viewer's.
 * @param mine whether the viewer owns this offer.
 * @param quality the offered quality (0–1000).
 * @param amount the offered quantity in SCU.
 * @param releasedAt when the offer was released.
 * @param remark the raw Markdown trade remark.
 * @param interestCount how many members registered interest.
 * @param interestedHandles the interessenten handles — only for the owner, otherwise {@code null}.
 * @param viewerInterested whether the viewer registered interest.
 * @param status the offer status name (ACTIVE / DEACTIVATED).
 * @param version the optimistic-lock version.
 */
public record MaterialExchangeOfferDto(
    UUID id,
    MaterialReferenceDto material,
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
    @BackendEnumAsString String status,
    Long version) {}
