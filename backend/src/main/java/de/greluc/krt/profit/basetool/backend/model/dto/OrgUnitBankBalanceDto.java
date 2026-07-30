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

import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The current balance of one bank account visible on the org-unit bank page (REQ-BANK-021/-028,
 * F1). Deliberately balance-only: it carries the account's identity (so the frontend can label the
 * card and target a booking request against the org unit) and its current balance, but never the
 * transaction history, the holder distribution or the audit trail — those stay a bank-staff
 * surface.
 *
 * <p>Two flavours share this shape. Most instances describe an <b>org-unit account</b> (the
 * caller's own-level account or a subordinate one reached by the cascading view); for those the
 * {@code orgUnit*} fields are populated. Since REQ-BANK-028 a Bereich/OL overseer additionally sees
 * the cartel-wide <b>special accounts</b> (Sonderkonten, {@link BankAccountType#SPECIAL}); those
 * belong to no org unit, so their {@code orgUnitId}/{@code orgUnitName}/{@code orgUnitKind} are
 * {@code null}, {@code canRequest} is always {@code false} (view-only), and the frontend labels the
 * card by {@link #type} instead of the org-unit shorthand. Only {@link BankAccountStatus#ACTIVE}
 * accounts are ever listed (REQ-BANK-028).
 *
 * @param accountId the account's id (for an own-level org-unit account it keys the F2 request form;
 *     never used to reach a staff endpoint)
 * @param accountNo the human-readable {@code KB-<n>} account number
 * @param accountName the account's display name
 * @param status the account lifecycle status (always {@link BankAccountStatus#ACTIVE} here — closed
 *     accounts are filtered out, REQ-BANK-028)
 * @param type the account type ({@code ORG_UNIT} / {@code AREA} / {@code CARTEL} / {@code
 *     SPECIAL}); lets the frontend label a special account (Sonderkonto) that carries no org-unit
 *     identity
 * @param orgUnitId the owning org unit's id, or {@code null} for a special account (Sonderkonto)
 * @param orgUnitName the owning org unit's long-form name, or {@code null} for a special account
 * @param orgUnitShorthand the owning org unit's 3–5 letter shorthand, or {@code null} for a special
 *     account or a legacy org-unit row without one
 * @param orgUnitKind the owning org unit's kind (SQUADRON / SPECIAL_COMMAND / BEREICH /
 *     ORGANISATIONSLEITUNG), or {@code null} for a special account
 * @param balance the account's current balance in whole aUEC (compute-on-read, ADR-0010)
 * @param canRequest {@code true} iff this is the caller's <em>own-level</em> org-unit account, so
 *     the F2 booking-request affordance applies (epic #692 Phase 6, owner decision Q4). {@code
 *     false} for a subordinate account reached through the cascading view <em>and</em> for every
 *     special account (Sonderkonto) — those are view-only, and the backend rejects a request
 *     against them.
 * @param delta30d the net balance change over the last 30 days (signed), so the card can show the
 *     same trend figure as the bank dashboard (REQ-BANK-016)
 * @param sparkline the end-of-day balances of the last 30 days, oldest first, last entry = current
 *     balance; the frontend scales these into the inline SVG sparkline shown on the card
 * @param balanceTarget the account's aspirational balance goal (REQ-BANK-036), or {@code null} when
 *     none is set; the card shows the progress towards it
 * @param canManageSettings {@code true} iff the caller may open the account's settings (set the
 *     balance target and/or configure who else may view it) — the responsible holder of an org-unit
 *     account, or an OL member for a Sonderkonto. Drives the per-card settings affordance.
 * @param approvalLimit the caller's resolved approval limit for this account (REQ-BANK-041), or
 *     {@code null} when no limit applies to them — which means the request needs the responsible
 *     holder's approval, <em>unless</em> {@code approvalExempt} is set. The request modal warns
 *     whenever approval would be required.
 * @param approvalExempt {@code true} iff the caller is this account's responsible holder and is
 *     therefore bound by no approval ceiling at all (REQ-BANK-041, owner decision): a holder
 *     disposes freely over their own account, so the modal never warns. The request still has to be
 *     confirmed by a bank employee — only the holder's extra sign-off falls away.
 */
public record OrgUnitBankBalanceDto(
    UUID accountId,
    String accountNo,
    String accountName,
    BankAccountStatus status,
    BankAccountType type,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    @Nullable String orgUnitShorthand,
    @Nullable OrgUnitKind orgUnitKind,
    BigDecimal balance,
    boolean canRequest,
    BigDecimal delta30d,
    List<BigDecimal> sparkline,
    @Nullable BigDecimal balanceTarget,
    boolean canManageSettings,
    @Nullable BigDecimal approvalLimit,
    boolean approvalExempt) {}
