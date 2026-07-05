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
import de.greluc.krt.profit.basetool.backend.model.Department;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One dashboard KPI card (D1 mockup, REQ-BANK-016): current balance, sign-colored 30-day delta and
 * the server-computed daily balance series the frontend renders as an inline SVG sparkline.
 *
 * <p>The optional Bereich fields (REQ-BANK-016 grouping) let the frontend group the cards by area:
 * an {@code AREA} account's Bereich is its own owning org unit, a Staffel/SK ({@code ORG_UNIT})
 * account's Bereich is that org unit's parent; {@code CARTEL} / {@code CARTEL_BANK} / {@code
 * SPECIAL} and org units without a Bereich carry {@code null}. Resolving the Bereich is an
 * owner-label read, never an org-unit scope decision (REQ-BANK-008).
 *
 * @param id the account's id (the card links to the detail page)
 * @param accountNo the account's display number
 * @param name the account's display name
 * @param type the account type (rendered as chip)
 * @param status lifecycle state — closed accounts render dimmed ({@code .kpi-card--closed})
 * @param balance current compute-on-read balance
 * @param delta30d net change over the last 30 days (signed)
 * @param sparkline end-of-day balances of the last 30 days, oldest first, last entry = current
 *     balance; the frontend scales these into the sparkline polyline
 * @param bereichId the owning Bereich's id for the by-Bereich grouping, or {@code null}
 * @param bereichName the owning Bereich's display name, or {@code null}
 * @param bereichDepartment the owning Bereich's department (its frozen Bereichsfarbe drives the
 *     group's colour tint), or {@code null}
 */
public record BankDashboardAccountDto(
    UUID id,
    String accountNo,
    String name,
    BankAccountType type,
    BankAccountStatus status,
    BigDecimal balance,
    BigDecimal delta30d,
    List<BigDecimal> sparkline,
    @Nullable UUID bereichId,
    @Nullable String bereichName,
    @Nullable Department bereichDepartment) {}
