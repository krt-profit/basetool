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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A booking request ready to be persisted by {@link BankBookingRequestService#create}: the explicit
 * requester, the payload and the approval routing the policy admitted it with (REQ-BANK-022,
 * ADR-0352).
 *
 * @param requester the member the request is raised for and stamped with; {@link BankActor#NOBODY}
 *     stamps no requester
 * @param accountId the source account (already view-checked by the caller)
 * @param type deposit, withdrawal or transfer
 * @param amount the requested whole-aUEC amount
 * @param note the requester's optional note
 * @param justification the requester's optional Begr&uuml;ndung; required for a {@code WITHDRAWAL}
 *     / {@code TRANSFER} from a {@linkplain BankAccountType#requiresDebitJustification()
 *     justification-mandating} account (REQ-BANK-045)
 * @param targetAccountId the transfer destination, or {@code null} for deposit/withdrawal
 * @param routing the approval snapshot: whether the amount exceeds the requester's approval limit,
 *     that limit and the approver class a flagged request needs
 * @param splitEnabled whether a deposit is split across the squadron accounts on confirmation
 *     (REQ-BANK-044); always {@code false} for withdrawal/transfer
 * @param splitPercent the whole percent (1–100) to distribute; {@code null} unless {@code
 *     splitEnabled}
 * @param counterpartyUserId the Empf&auml;nger of a {@code WITHDRAWAL} request (REQ-BANK-055), or
 *     {@code null} to derive the requester at confirmation
 * @param counterpartyOrgUnitId that Empf&auml;nger's org unit, or {@code null}
 * @param purpose what another module raised the request for (REQ-BANK-061), or {@code null}
 * @param clientId the connected application that raised it through the exchange (REQ-BANK-067), or
 *     {@code null} for a request raised in the Basetool
 */
public record BankBookingRequestDraft(
    @NotNull BankActor requester,
    @NotNull UUID accountId,
    @NotNull BankBookingRequestType type,
    @NotNull BigDecimal amount,
    @Nullable String note,
    @Nullable String justification,
    @Nullable UUID targetAccountId,
    @NotNull BankAdmission.Admitted routing,
    boolean splitEnabled,
    @Nullable BigDecimal splitPercent,
    @Nullable UUID counterpartyUserId,
    @Nullable UUID counterpartyOrgUnitId,
    @Nullable BankPurpose purpose,
    @Nullable String clientId) {}
