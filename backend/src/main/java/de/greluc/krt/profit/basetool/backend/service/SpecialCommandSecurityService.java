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
 * Authorization helper for Spezialkommando-administration {@code @PreAuthorize} expressions.
 * Mirrors the {@link MissionSecurityService} pattern — methods on this bean are referenced from
 * SpEL ({@code @specialCommandSecurityService.canManageMembers(#id, authentication)}) and translate
 * the "can the caller manage members of this SK" question into a boolean by combining the caller's
 * authorities with the membership row on the target SK.
 *
 * <p>The rule, recorded in {@code SPEZIALKOMMANDO_PLAN.md} §2 (D2): an admin may manage any SK's
 * member list; a non-admin caller may manage a specific SK's members iff their membership on that
 * exact SK carries {@code role = SK_LEAD}. The same verdict also gates the single-SK read {@code
 * GET /api/v1/special-commands/{id}}, so the lead's member page can render its SK header. The lead
 * seat itself is set through a dedicated endpoint gated to admin or the Bereichsleiter of the SK's
 * parent Bereich, so a Lead cannot promote themselves or someone else to Lead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SpecialCommandSecurityService {

  private final AuthHelperService authHelperService;
  private final OrgUnitMembershipRepository membershipRepository;

  /**
   * Answers the SpEL-level question whether the calling principal may manage members of the given
   * Spezialkommando.
   *
   * <ul>
   *   <li>ADMIN — always {@code true}; SK lifecycle and per-SK admin actions are part of the global
   *       admin surface.
   *   <li>Anonymous / unauthenticated — always {@code false}; the endpoints under {@code
   *       /api/v1/special-commands/{id}/members/**} are not part of the public surface.
   *   <li>Authenticated non-admin — {@code true} iff the caller's membership on the exact SK
   *       referenced by {@code specialCommandId} carries {@code role = SK_LEAD}. A Lead may NOT
   *       manage members of any other SK they are merely a regular member of.
   * </ul>
   *
   * <p>A non-existent SK id is treated as denied (no special-case 404 surface from the
   * authorisation layer) — the controller layer will surface the 404 separately when the service
   * call hits {@link SpecialCommandService#getSpecialCommandById} with the same id.
   *
   * @param specialCommandId the SK whose member list the caller wants to manage; never {@code
   *     null}.
   * @param authentication current Spring Security authentication; may be {@code null} for anonymous
   *     calls (the framework hands SpEL a non-null but anonymous principal in that case, so the
   *     null check below is defensive).
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
        // SK-Lead now lives on the unified rank (epic #800, REQ-ROLE-001); is_lead was dropped in
        // the Phase 5 cleanup (V187).
        .map(m -> m.getRole() == MembershipRole.SK_LEAD)
        .orElse(false);
  }
}
