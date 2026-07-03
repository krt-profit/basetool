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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Response payload for one bank account (epic #556, REQ-BANK-001). The balance is computed on read
 * from the ledger (ADR-0010) and joined in by the service — it is not a stored column.
 *
 * @param id the account's id
 * @param accountNo server-generated, never-reused display number ({@code KB-0042})
 * @param name display name
 * @param type the organizational layer the account belongs to
 * @param status lifecycle state ({@code ACTIVE} / {@code CLOSED})
 * @param orgUnit owning org unit reference for {@code ORG_UNIT} accounts, else {@code null}
 * @param areaName free-form Bereich name for {@code AREA} accounts, else {@code null}
 * @param balance current compute-on-read balance (signed whole aUEC)
 * @param balanceTarget aspirational balance goal (REQ-BANK-036), or {@code null} when none is set
 * @param employeeApprovalCeiling the KRT-account bank-employee approval ceiling T1 (REQ-BANK-047),
 *     or {@code null} for a non-CARTEL account or an unconfigured KRT account
 * @param areaLeadApprovalCeiling the KRT-account Bereichsleiter-Profit ceiling T2 (REQ-BANK-047),
 *     or {@code null} for a non-CARTEL account or an unconfigured upper band
 * @param version optimistic-locking version the client must echo on mutations (REQ-BANK-018)
 * @param createdAt creation instant (UTC)
 */
public record BankAccountDto(
    UUID id,
    String accountNo,
    String name,
    BankAccountType type,
    BankAccountStatus status,
    @Nullable OrgUnitReferenceDto orgUnit,
    @Nullable String areaName,
    BigDecimal balance,
    @Nullable BigDecimal balanceTarget,
    @Nullable BigDecimal employeeApprovalCeiling,
    @Nullable BigDecimal areaLeadApprovalCeiling,
    Long version,
    Instant createdAt) {}
