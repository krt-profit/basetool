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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The per-tier approval limits of one bank account (REQ-BANK-041), with the available role buckets
 * and whether the calling surface may edit them.
 *
 * <p>A missing role tier, a {@code null} {@link #allMembersLimit} or a user absent from {@link
 * #userLimits} means no limit (no approval needed).
 *
 * @param canEdit whether the calling surface may set/clear limits; only the org-unit bank settings
 *     surface sets this {@code true}
 * @param configurable whether this account type carries per-audience limits ({@code ORG_UNIT} /
 *     {@code AREA}); {@code false} for the KRT account, which uses the amount-tiered ladder
 *     (REQ-BANK-047)
 * @param allMembersSupported whether the all-members tier applies to this account
 * @param areaMembersSupported whether the "Mitglieder des Bereichs" cascade tier applies (only
 *     {@code AREA} accounts, REQ-BANK-048)
 * @param availableRoleCodes the role buckets that may carry a limit, in display order; empty for SK
 *     / CARTEL accounts
 * @param roleLimits the configured role-bucket limits, keyed by role code
 * @param allMembersLimit the configured all-members limit, or {@code null} when none is set
 * @param areaMembersLimit the configured "Mitglieder des Bereichs" limit, or {@code null} when none
 *     is set
 * @param userLimits the configured individual-user limits, with resolved display names
 */
public record BankApprovalLimitsDto(
    boolean canEdit,
    boolean configurable,
    boolean allMembersSupported,
    boolean areaMembersSupported,
    List<String> availableRoleCodes,
    Map<String, BigDecimal> roleLimits,
    @Nullable BigDecimal allMembersLimit,
    @Nullable BigDecimal areaMembersLimit,
    List<BankApprovalLimitUserDto> userLimits) {

  /**
   * Whether any approval limit is configured at all — gates the read-only display block (no block
   * is rendered when an account carries no limits).
   *
   * @return {@code true} iff at least one role, all-members, area-members or user limit is set
   */
  public boolean hasAny() {
    return !roleLimits.isEmpty()
        || allMembersLimit != null
        || areaMembersLimit != null
        || !userLimits.isEmpty();
  }
}
