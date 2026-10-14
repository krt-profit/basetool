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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A trade deal with its tranches, deliveries and settlement (REQ-PUR-007 … REQ-PUR-021). The
 * header, each tranche, each delivery and the settlement carry their own version (REQ-PUR-026).
 *
 * @param id the deal
 * @param dealNo the running number shown to people
 * @param needId the need the deal serves
 * @param raisingOrgUnit the need's raising unit
 * @param material the material, or {@code null} for a game item
 * @param gameItem the game item, or {@code null} for a material
 * @param quantityType {@code SCU}, {@code PIECE} or {@code ITEM}
 * @param qualityTier the tier the goods must reach, or {@code null} for a game item
 * @param amount the amount bought
 * @param acceptedAmount what the raising unit accepted so far
 * @param pendingAmount what is delivered and not yet decided
 * @param unitPrice the price of one unit in whole aUEC
 * @param total the deal's total in whole aUEC
 * @param uexReferencePrice the UEX buy price of the material, or {@code null}
 * @param paymentModel how the counterparty is paid
 * @param riskClass the computed or raised risk class
 * @param escortWish whether an escort is wished
 * @param missionId the escorting Einsatz, or {@code null}
 * @param status the deal's state
 * @param deliveryClosed whether the delegate closed the delivery
 * @param settlementDueAt when the settlement is due, or {@code null}
 * @param overdue whether the settlement is past its due date
 * @param requestedAmount the money requested and not refused, in whole aUEC
 * @param fundedAmount the money the bank confirmed, in whole aUEC
 * @param partner the counterparty
 * @param delegateName the delegate's display name, or {@code null} once erased
 * @param isMine whether the caller is the deal's delegate
 * @param tranches the money tranches, oldest first
 * @param deliveries the deliveries, oldest first
 * @param settlement the settlement, or {@code null} before one was recorded
 * @param canEdit whether the caller may edit the draft
 * @param canRequestTranche whether the caller may request money now
 * @param canRecordDelivery whether the caller may record a delivery now
 * @param canCloseDelivery whether the caller may close the delivery now
 * @param canSettle whether the caller may record the settlement now
 * @param canCancel whether the caller may cancel the deal now
 * @param version the optimistic-locking version of the header to echo
 * @param createdAt when the deal was created
 */
public record DealDto(
    UUID id,
    long dealNo,
    UUID needId,
    PurchasingRefDto raisingOrgUnit,
    @Nullable PurchasingRefDto material,
    @Nullable PurchasingRefDto gameItem,
    String quantityType,
    @Nullable PurchasingTierRefDto qualityTier,
    double amount,
    double acceptedAmount,
    double pendingAmount,
    long unitPrice,
    long total,
    @Nullable Double uexReferencePrice,
    PaymentModel paymentModel,
    RiskClassCode riskClass,
    boolean escortWish,
    @Nullable UUID missionId,
    DealStatus status,
    boolean deliveryClosed,
    @Nullable Instant settlementDueAt,
    boolean overdue,
    long requestedAmount,
    long fundedAmount,
    DealPartnerDto partner,
    @Nullable String delegateName,
    boolean isMine,
    List<TrancheDto> tranches,
    List<DeliveryDto> deliveries,
    @Nullable SettlementDto settlement,
    boolean canEdit,
    boolean canRequestTranche,
    boolean canRecordDelivery,
    boolean canCloseDelivery,
    boolean canSettle,
    boolean canCancel,
    Long version,
    Instant createdAt) {}
