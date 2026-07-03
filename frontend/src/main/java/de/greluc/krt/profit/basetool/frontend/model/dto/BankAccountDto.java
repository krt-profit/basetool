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
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend's bank-account payload (epic #556). The type and status arrive as
 * enum names rendered through i18n keys ({@code bank.account.type.*} / {@code
 * bank.account.status.*}); the balance is the backend's compute-on-read sum.
 *
 * @param id the account's id
 * @param accountNo server-generated display number ({@code KB-0042})
 * @param name display name
 * @param type account type enum name ({@code ORG_UNIT}, {@code AREA}, {@code CARTEL}, {@code
 *     CARTEL_BANK}, {@code SPECIAL})
 * @param status lifecycle enum name ({@code ACTIVE} / {@code CLOSED})
 * @param orgUnit owning org unit reference for org-unit accounts, else {@code null}
 * @param areaName free-form Bereich name for area accounts, else {@code null}
 * @param balance current balance (signed whole aUEC)
 * @param balanceTarget aspirational balance goal (REQ-BANK-036), or {@code null} when none is set
 * @param employeeApprovalCeiling the KRT-account bank-employee approval ceiling T1 (REQ-BANK-047),
 *     or {@code null} for a non-CARTEL / unconfigured account
 * @param areaLeadApprovalCeiling the KRT-account Bereichsleiter-Profit ceiling T2 (REQ-BANK-047),
 *     or {@code null} for a non-CARTEL account / unconfigured upper band
 * @param version optimistic-locking version to echo on mutations
 * @param createdAt creation instant (UTC)
 */
public record BankAccountDto(
    UUID id,
    String accountNo,
    String name,
    @BackendEnumAsString String type,
    @BackendEnumAsString String status,
    @Nullable OrgUnitReferenceDto orgUnit,
    @Nullable String areaName,
    BigDecimal balance,
    @Nullable BigDecimal balanceTarget,
    @Nullable BigDecimal employeeApprovalCeiling,
    @Nullable BigDecimal areaLeadApprovalCeiling,
    Long version,
    Instant createdAt) {}
