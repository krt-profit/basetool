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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.supply.api.DeclineReason;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A donation as the pages show it (REQ-SUP-012). The donor's name is left {@code null} for a caller
 * who may not see it: everyone but the donor, the governing leadership, admins and the receiving
 * unit's managers.
 *
 * @param id the donation
 * @param receivingOrgUnitId the receiving unit
 * @param receivingOrgUnitName the receiving unit's name
 * @param material the material, or {@code null} for a game item
 * @param gameItem the game item with a component's type, size, grade and class (REQ-SUP-022), or
 *     {@code null} for a material
 * @param quality the quality of a material donation, or {@code null}
 * @param quantityType {@code SCU}, {@code PIECE} or {@code ITEM}
 * @param pledgedAmount the pledged amount
 * @param receivedAmount the confirmed amount, or {@code null} while not received
 * @param needId the need the donation is credited to, or {@code null}
 * @param status the lifecycle state
 * @param declineReason why it was declined, or {@code null}
 * @param donorName the donor's display name, or {@code null} when hidden or not recorded
 * @param donorIsMe whether the caller is the donor
 * @param fromStock whether the pledge is earmarked on a Lager row
 * @param receivedAt when the receipt was confirmed, or {@code null}
 * @param canReceive whether the caller may confirm or decline the pledge now
 * @param canWithdraw whether the caller may withdraw the pledge now
 * @param version the optimistic-locking version
 * @param createdAt when the donation was pledged or recorded
 */
public record DonationDto(
    UUID id,
    UUID receivingOrgUnitId,
    String receivingOrgUnitName,
    @Nullable SupplyRefDto material,
    @Nullable GameItemReferenceDto gameItem,
    @Nullable Integer quality,
    String quantityType,
    Double pledgedAmount,
    @Nullable Double receivedAmount,
    @Nullable UUID needId,
    DonationStatus status,
    @Nullable DeclineReason declineReason,
    @Nullable String donorName,
    boolean donorIsMe,
    boolean fromStock,
    @Nullable Instant receivedAt,
    boolean canReceive,
    boolean canWithdraw,
    Long version,
    Instant createdAt) {}
