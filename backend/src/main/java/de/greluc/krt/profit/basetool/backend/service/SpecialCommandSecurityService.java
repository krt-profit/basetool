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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SpEL authorization helper for Spezialkommando administration, used by the member-management
 * {@code @PreAuthorize} expressions.
 *
 * <p>An admin may manage any SK's members; a non-admin only those of an SK where their membership
 * carries {@code SK_LEAD}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SpecialCommandSecurityService {

  private final AuthHelperService authHelperService;
  private final OrgUnitMembershipRepository membershipRepository;

  /**
   * Returns whether the caller may manage members of the given Spezialkommando: always for ADMIN,
   * never for an unauthenticated caller, otherwise iff the caller's membership on exactly that SK
   * carries {@code role = SK_LEAD}.
   *
   * <p>An unknown SK id is denied.
   *
   * @param specialCommandId the SK whose member list the caller wants to manage; never {@code
   *     null}.
   * @param authentication current Spring Security authentication; may be {@code null}.
   * @return {@code true} iff the caller may manage members of {@code specialCommandId}.
   */
  public boolean canManageMembers(@NotNull UUID specialCommandId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    if (authHelperService.isAdmin()) {
      return true;
    }
    return authHelperService
        .currentUserId()
        .flatMap(
            userId ->
                membershipRepository.findById(new OrgUnitMembershipId(userId, specialCommandId)))
        .map(m -> m.getRole() == MembershipRole.SK_LEAD)
        .orElse(false);
  }
}
