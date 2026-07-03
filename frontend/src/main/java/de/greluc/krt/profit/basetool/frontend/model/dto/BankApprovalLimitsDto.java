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
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of an account's per-tier approval limits (REQ-BANK-041), shared by both
 * account-detail surfaces: the read-only figures everyone may see plus the editor structure
 * (available role buckets + whether the calling surface may edit). A tier missing from {@link
 * #roleLimits} / a {@code null} {@link #allMembersLimit} / a user not in {@link #userLimits} means
 * unlimited (no approval needed).
 *
 * @param canEdit whether the calling surface may set/clear limits
 * @param configurable whether this account type carries per-audience approval limits at all
 * @param allMembersSupported whether the all-members tier applies to this account
 * @param areaMembersSupported whether the "Mitglieder des Bereichs" cascade tier applies — only for
 *     AREA (Bereichskonto) accounts (REQ-BANK-048)
 * @param availableRoleCodes the role buckets that may carry a limit, in display order
 * @param roleLimits the configured role-bucket limits, keyed by role code
 * @param allMembersLimit the configured all-members limit, or {@code null}
 * @param areaMembersLimit the configured "Mitglieder des Bereichs" cascade limit, or {@code null}
 *     (REQ-BANK-048)
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
   * Whether any approval limit is configured at all — gates the read-only display block.
   *
   * @return {@code true} iff at least one role, all-members, area-members or user limit is set
   */
  public boolean hasAny() {
    return (roleLimits != null && !roleLimits.isEmpty())
        || allMembersLimit != null
        || areaMembersLimit != null
        || (userLimits != null && !userLimits.isEmpty());
  }
}
