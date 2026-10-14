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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code DealDto}; must match it field for field.
 *
 * @param id the deal
 * @param dealNo the running number
 * @param needId the need
 * @param raisingOrgUnit the raising unit
 * @param material the material, or {@code null}
 * @param gameItem the game item, or {@code null}
 * @param quantityType {@code SCU}, {@code PIECE} or {@code ITEM}
 * @param qualityTier the tier, or {@code null}
 * @param amount the amount bought
 * @param acceptedAmount what the raising unit accepted
 * @param pendingAmount what is delivered and undecided
 * @param unitPrice the unit price in whole aUEC
 * @param total the total in whole aUEC
 * @param uexReferencePrice the UEX buy price, or {@code null}
 * @param paymentModel how the counterparty is paid
 * @param riskClass the risk class
 * @param escortWish whether an escort is wished
 * @param missionId the escorting Einsatz, or {@code null}
 * @param status the deal state
 * @param deliveryClosed whether the delegate closed the delivery
 * @param settlementDueAt when the settlement is due, or {@code null}
 * @param overdue whether the settlement is overdue
 * @param requestedAmount the money requested and not refused
 * @param fundedAmount the money the bank confirmed
 * @param partner the counterparty
 * @param delegateName the delegate's name, or {@code null}
 * @param isMine whether the caller is the deal's delegate
 * @param tranches the tranches
 * @param deliveries the deliveries
 * @param settlement the settlement, or {@code null}
 * @param canEdit whether the caller may edit the draft
 * @param canRequestTranche whether the caller may request money
 * @param canRecordDelivery whether the caller may record a delivery
 * @param canCloseDelivery whether the caller may close the delivery
 * @param canSettle whether the caller may settle
 * @param canCancel whether the caller may cancel
 * @param version the optimistic-locking version of the header
 * @param createdAt when the deal was created
 */
@DtoMirror
public record DealDto(
    UUID id,
    long dealNo,
    UUID needId,
    PurchasingRefDto raisingOrgUnit,
    PurchasingRefDto material,
    PurchasingRefDto gameItem,
    String quantityType,
    PurchasingTierRefDto qualityTier,
    double amount,
    double acceptedAmount,
    double pendingAmount,
    long unitPrice,
    long total,
    Double uexReferencePrice,
    @BackendEnumAsString String paymentModel,
    @BackendEnumAsString String riskClass,
    boolean escortWish,
    UUID missionId,
    @BackendEnumAsString String status,
    boolean deliveryClosed,
    Instant settlementDueAt,
    boolean overdue,
    long requestedAmount,
    long fundedAmount,
    DealPartnerDto partner,
    String delegateName,
    boolean isMine,
    List<TrancheDto> tranches,
    List<DeliveryDto> deliveries,
    SettlementDto settlement,
    boolean canEdit,
    boolean canRequestTranche,
    boolean canRecordDelivery,
    boolean canCloseDelivery,
    boolean canSettle,
    boolean canCancel,
    Long version,
    Instant createdAt) {}
