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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SpEL authorization helper for Spezialkommando member management, used by the
 * {@code @PreAuthorize} expressions of the SK read and its roster endpoints (REQ-ORG-005,
 * REQ-ROLE-004).
 *
 * <p>An admin may manage any SK's members; a non-admin only those of an active SK where their
 * membership carries {@code SK_LEAD}, and only while they hold {@code KRT Member} (REQ-SEC-015,
 * REQ-ORG-032). The active context is checked beside these verdicts at each endpoint, never here.
 */
@Service("specialCommandSecurityService")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SpecialCommandSecurityService {

  private final AuthHelperService authHelperService;
  private final OrgUnitMembershipRepository membershipRepository;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Returns whether the caller may manage members of the given Spezialkommando: always for ADMIN,
   * never for an unauthenticated caller, otherwise iff the caller holds {@code KRT Member}, their
   * membership on exactly that SK carries {@code role = SK_LEAD}, and the SK is active.
   *
   * <p>An unknown SK id is denied.
   *
   * @param specialCommandId the SK whose member list the caller wants to manage; never {@code
   *     null}.
   * @param authentication current Spring Security authentication; may be {@code null}.
   * @return {@code true} iff the caller may manage members of {@code specialCommandId}.
   */
  public boolean canManageMembers(
      @NotNull UUID specialCommandId, @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication)) {
      return false;
    }
    if (authHelperService.isAdmin()) {
      return true;
    }
    return authHelperService
        .currentUserId()
        .map(callerId -> leads(callerId, specialCommandId))
        .orElse(false);
  }

  /**
   * Returns whether the caller may change the Logistiker / Einsatzleiter flags of, or remove, the
   * member's row on the Spezialkommando: always for ADMIN; for a non-admin who may {@linkplain
   * #canManageMembers manage its members} only on another member's row that does not carry {@code
   * SK_LEAD}. A lead's seat is touched only by an admin or, through the lead toggle, by the parent
   * Bereichsleiter (REQ-ROLE-004). A missing row passes, so the write answers its own 404.
   *
   * @param specialCommandId the SK whose roster row is written; never {@code null}.
   * @param userId the member whose row is written; never {@code null}.
   * @param authentication current Spring Security authentication; may be {@code null}.
   * @return {@code true} iff the caller may write the member's row.
   */
  public boolean canManageMember(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication)) {
      return false;
    }
    if (authHelperService.isAdmin()) {
      return true;
    }
    UUID callerId = authHelperService.currentUserId().orElse(null);
    return callerId != null
        && !callerId.equals(userId)
        && leads(callerId, specialCommandId)
        && roleOf(userId, specialCommandId) != MembershipRole.SK_LEAD;
  }

  /**
   * {@code true} when the authentication is missing or anonymous (the framework hands SpEL a
   * non-null anonymous token in that case), so the caller is denied before any lookup.
   *
   * @param authentication the SpEL-supplied authentication; may be {@code null}.
   * @return {@code true} iff the caller is not an authenticated principal.
   */
  private static boolean denyUnauthenticated(@Nullable Authentication authentication) {
    return authentication == null || !authentication.isAuthenticated();
  }

  /**
   * Whether the non-admin caller leads the Spezialkommando: they hold {@code KRT Member}, without
   * which a seat confers nothing (REQ-SEC-015), their membership on it carries {@code SK_LEAD}, and
   * the SK is active, since a deactivated unit's seat confers nothing either (REQ-ORG-032).
   *
   * @param callerId the caller's account id; never {@code null}.
   * @param specialCommandId the Spezialkommando; never {@code null}.
   * @return {@code true} iff the caller is a lead of the active SK with {@code KRT Member}.
   */
  private boolean leads(@NotNull UUID callerId, @NotNull UUID specialCommandId) {
    return authHelperService.holdsKrtMember()
        && roleOf(callerId, specialCommandId) == MembershipRole.SK_LEAD
        && orgUnitRepository.existsByIdAndActiveTrue(specialCommandId);
  }

  /**
   * The rank a user holds on the Spezialkommando, independent of the caller.
   *
   * @param userId the user; never {@code null}.
   * @param specialCommandId the Spezialkommando; never {@code null}.
   * @return the membership rank, or {@code null} when the user is no member of the SK.
   */
  @Nullable
  private MembershipRole roleOf(@NotNull UUID userId, @NotNull UUID specialCommandId) {
    return membershipRepository
        .findById(new OrgUnitMembershipId(userId, specialCommandId))
        .map(OrgUnitMembership::getRole)
        .orElse(null);
  }
}
