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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend's org-unit bank balance payload (epic #666 F1,
 * REQ-BANK-021/-027/-028). Deliberately balance-only — it carries the account's identity (so the
 * officer/lead page can label the card and target a booking request at the org unit) and the
 * compute-on-read balance, never the transaction history, holders or audit. The status / type /
 * org-unit kind arrive as enum names rendered through i18n keys.
 *
 * <p>An org-unit account populates the {@code orgUnit*} fields; a special account (Sonderkonto,
 * REQ-BANK-028, only visible to Bereich/OL overseers and admins) carries {@code null} org-unit
 * fields and {@code canRequest = false}, and the page labels its card by {@link #type}. Only active
 * accounts are ever delivered.
 *
 * @param accountId the account's id
 * @param accountNo the server-generated display number ({@code KB-0042})
 * @param accountName the account's display name
 * @param status lifecycle enum name (always {@code ACTIVE} here — closed accounts are filtered out)
 * @param type account-type enum name ({@code ORG_UNIT} / {@code AREA} / {@code CARTEL} / {@code
 *     SPECIAL}); used to label a special account that has no org-unit identity
 * @param orgUnitId the owning org unit's id, or {@code null} for a special account
 * @param orgUnitName the owning org unit's long-form name, or {@code null} for a special account
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null}
 * @param orgUnitKind kind enum name ({@code SQUADRON} / {@code SPECIAL_COMMAND} / {@code BEREICH} /
 *     {@code ORGANISATIONSLEITUNG}), or {@code null} for a special account
 * @param balance the current balance (signed whole aUEC)
 * @param canRequest {@code true} iff this is the caller's own-level org-unit account (the request
 *     button is shown); {@code false} for a view-only subordinate account reached by the cascade
 *     (epic #692 Phase 6, owner decision Q4) and for every special account
 * @param delta30d the net balance change over the last 30 days (signed), rendered as the
 *     sign-colored trend figure on the card (REQ-BANK-016, mirroring the bank dashboard)
 * @param sparkline the end-of-day balances of the last 30 days, oldest first, last entry = current
 *     balance; scaled into the card's inline SVG sparkline by {@link
 *     de.greluc.krt.profit.basetool.frontend.controller.BankSparkline}
 * @param balanceTarget the aspirational balance goal (REQ-BANK-036), or {@code null} when none is
 *     set; the card shows progress towards it
 * @param canManageSettings {@code true} iff the caller may open the account's settings (set the
 *     target and/or configure who else may view it); drives the per-card settings affordance
 * @param approvalLimit the caller's resolved approval limit for this account (REQ-BANK-041), or
 *     {@code null} when no limit applies — which means approval is required, unless {@code
 *     approvalExempt} is set; the request modal warns whenever approval would be required
 * @param approvalExempt {@code true} iff the caller is this account's responsible holder and is
 *     bound by no approval ceiling (REQ-BANK-041, owner decision); rides onto the source-account
 *     option as {@code data-exempt} so the modal suppresses the warning
 */
public record OrgUnitBankBalanceDto(
    UUID accountId,
    String accountNo,
    String accountName,
    @BackendEnumAsString String status,
    @BackendEnumAsString String type,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    @Nullable String orgUnitShorthand,
    @Nullable @BackendEnumAsString String orgUnitKind,
    BigDecimal balance,
    boolean canRequest,
    BigDecimal delta30d,
    List<BigDecimal> sparkline,
    @Nullable BigDecimal balanceTarget,
    boolean canManageSettings,
    @Nullable BigDecimal approvalLimit,
    boolean approvalExempt) {}
