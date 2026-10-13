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

package de.greluc.krt.profit.basetool.frontend.supply.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code DonationDto}; must match it field for field. The donor's
 * name is {@code null} for a caller who may not read it.
 *
 * @param id the donation
 * @param receivingOrgUnitId the receiving unit
 * @param receivingOrgUnitName the receiving unit's name
 * @param material the material, or {@code null} for a game item
 * @param gameItem the game item, or {@code null} for a material
 * @param quality the quality of a material donation, or {@code null}
 * @param quantityType {@code SCU}, {@code PIECE} or {@code ITEM}
 * @param pledgedAmount the pledged amount
 * @param receivedAmount the confirmed amount, or {@code null}
 * @param needId the need the donation is credited to, or {@code null}
 * @param status the lifecycle state
 * @param declineReason why it was declined, or {@code null}
 * @param anonymous whether the donor asked to stay anonymous
 * @param donorName the donor's display name, or {@code null}
 * @param donorIsMe whether the caller is the donor
 * @param fromStock whether the pledge is earmarked on a Lager row
 * @param receivedAt when the receipt was confirmed, or {@code null}
 * @param canReceive whether the caller may confirm or decline the pledge now
 * @param canWithdraw whether the caller may withdraw the pledge now
 * @param version the optimistic-locking version
 * @param createdAt when the donation was pledged or recorded
 */
@DtoMirror
public record DonationDto(
    UUID id,
    UUID receivingOrgUnitId,
    String receivingOrgUnitName,
    SupplyRefDto material,
    SupplyRefDto gameItem,
    Integer quality,
    String quantityType,
    Double pledgedAmount,
    Double receivedAmount,
    UUID needId,
    @BackendEnumAsString String status,
    @BackendEnumAsString String declineReason,
    boolean anonymous,
    String donorName,
    boolean donorIsMe,
    boolean fromStock,
    Instant receivedAt,
    boolean canReceive,
    boolean canWithdraw,
    Long version,
    Instant createdAt) {}
