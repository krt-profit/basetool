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

/**
 * The read-only account detail an org-unit viewer sees when they open an account from the org-unit
 * bank page (REQ-BANK-038). Wraps the same {@link BankAccountDetailDto} the bank-staff detail page
 * uses — but its {@code capabilities} are always all-{@code false} (no deposit/withdraw/transfer) —
 * and adds the org-unit-side affordances the page renders: exporting a (holder-redacted) statement,
 * managing the account's visibility/target, and raising a booking request when this is the caller's
 * own-level account.
 *
 * @param detail the shared account detail (account + balance + target + 30-day delta + booking
 *     count + all-false capabilities)
 * @param canExportStatement whether the caller may export the (Halter-redacted) Kontoauszug —
 *     always {@code true} here (every viewer may), kept explicit so the frontend renders the button
 *     uniformly
 * @param canSetTarget whether the caller may set/clear the balance target (responsible holder)
 * @param canConfigureVisibility whether the caller may manage who else may view the account
 * @param canRequest whether the caller may raise a booking request against this account
 *     (REQ-BANK-039 — any viewer of a request-capable {@code ORG_UNIT} / {@code AREA} / {@code
 *     CARTEL} account)
 * @param canConfigureApprovalLimits whether the caller may set/clear this account's approval limits
 *     (REQ-BANK-041 — responsible holder / bank management / admin)
 * @param applicableLimit the caller's resolved approval limit for this account (REQ-BANK-041), or
 *     {@code null} when no limit applies to them — which means the request needs the responsible
 *     holder's approval, <em>unless</em> {@code approvalExempt} is set; the request form warns
 *     whenever approval would be required
 * @param approvalExempt {@code true} iff the caller is this account's responsible holder and is
 *     therefore bound by no approval ceiling at all (REQ-BANK-041, owner decision): a holder
 *     disposes freely over their own account, so the form never warns. The request still has to be
 *     confirmed by a bank employee — only the holder's extra sign-off falls away.
 */
public record OrgUnitBankAccountDetailDto(
    BankAccountDetailDto detail,
    boolean canExportStatement,
    boolean canSetTarget,
    boolean canConfigureVisibility,
    boolean canRequest,
    boolean canConfigureApprovalLimits,
    @org.jetbrains.annotations.Nullable java.math.BigDecimal applicableLimit,
    boolean approvalExempt) {}
