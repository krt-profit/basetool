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
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.RequestMemo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SpEL authorization for the delegated appointment ladder: may a non-admin caller appoint, change
 * or revoke a rank on an org unit (REQ-ROLE-004).
 *
 * <p>Granting a rank requires a strictly higher rank, decided only from the caller's own
 * memberships; the parent Bereich of a Staffel or SK is always read from its persisted edge. Admin
 * access is decided at the endpoint, never here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OrgRoleManagementSecurityService {

  /** Request-attribute key prefix of the per-request caller-membership memo. */
  private static final RequestMemo.Key<Map<UUID, List<OrgUnitMembership>>> CALLER_MEMBERSHIPS =
      RequestMemo.Key.of(OrgRoleManagementSecurityService.class, "callerMemberships");

  private final AuthHelperService authHelperService;
  private final OrgUnitMembershipRepository membershipRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final KommandoGroupRepository kommandoGroupRepository;

  /**
   * Whether the caller may assign the rank on the squadron: a Staffelleiter needs the parent
   * Bereich's Bereichsleiter, the lower ranks need the squadron's Staffelleiter.
   *
   * @param squadronId the squadron whose rank is being assigned
   * @param rank the rank to assign; non-squadron ranks deny
   * @param authentication the current authentication; anonymous or {@code null} denies
   * @return {@code true} iff the caller may make this assignment
   */
  public boolean canAssignSquadronRank(
      @NotNull UUID squadronId,
      @Nullable MembershipRole rank,
      @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication) || rank == null) {
      return false;
    }
    if (rank == MembershipRole.STAFFELLEITER) {
      return callerIsBereichsleiterOfParent(squadronId);
    }
    if (rank.isSquadronRank()) {
      return callerHasRoleOnUnit(squadronId, MembershipRole.STAFFELLEITER);
    }
    return false;
  }

  /**
   * Whether the caller may clear the member's current squadron rank, routed by that rank like
   * {@link #canAssignSquadronRank}.
   *
   * @param squadronId the squadron
   * @param userId the member whose rank is being cleared
   * @param authentication the current authentication; anonymous or {@code null} denies
   * @return {@code true} iff the caller may clear the member's rank
   */
  public boolean canRemoveSquadronRank(
      @NotNull UUID squadronId, @NotNull UUID userId, @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication)) {
      return false;
    }
    MembershipRole target = roleOf(userId, squadronId);
    if (target == MembershipRole.STAFFELLEITER) {
      return callerIsBereichsleiterOfParent(squadronId);
    }
    if (target != null && target.isSquadronRank()) {
      return callerHasRoleOnUnit(squadronId, MembershipRole.STAFFELLEITER);
    }
    return false;
  }

  /**
   * Whether the caller may create a Kommandogruppe in the squadron — the squadron's Staffelleiter.
   *
   * @param squadronId the squadron; never {@code null}.
   * @param authentication the current authentication; anonymous / {@code null} denies.
   * @return {@code true} iff the caller is the squadron's Staffelleiter.
   */
  public boolean canManageKommandoGroups(
      @NotNull UUID squadronId, @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication)) {
      return false;
    }
    return callerHasRoleOnUnit(squadronId, MembershipRole.STAFFELLEITER);
  }

  /**
   * Whether the caller is the Staffelleiter of the Kommandogruppe's persisted squadron and may thus
   * update or delete it.
   *
   * @param groupId the Kommandogruppe
   * @param authentication the current authentication; anonymous or {@code null} denies
   * @return {@code true} iff the caller is the Staffelleiter of the group's squadron
   */
  public boolean canManageKommandoGroup(
      @NotNull UUID groupId, @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication)) {
      return false;
    }
    return kommandoGroupRepository
        .findById(groupId)
        .map(g -> callerHasRoleOnUnit(g.getSquadron().getId(), MembershipRole.STAFFELLEITER))
        .orElse(false);
  }

  /**
   * Whether the caller may grant the Bereich role: a Bereichsleiter needs a pure OL member, a
   * Koordinator or Operator needs the Bereich's Bereichsleiter.
   *
   * @param bereichId the Bereich
   * @param role the Bereich role being granted
   * @param authentication the current authentication; anonymous or {@code null} denies
   * @return {@code true} iff the caller may grant the role
   */
  public boolean canAppointBereichRole(
      @NotNull UUID bereichId,
      @Nullable BereichLeadershipRole role,
      @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication) || role == null) {
      return false;
    }
    return switch (role) {
      case LEITER -> callerIsPureOlMember();
      case KOORDINATOR, OPERATOR -> callerHasRoleOnUnit(bereichId, MembershipRole.BEREICHSLEITER);
    };
  }

  /**
   * Whether the caller may remove the member's Bereich membership, routed by the member's current
   * rank like {@link #canAppointBereichRole}.
   *
   * @param bereichId the Bereich
   * @param userId the member to remove
   * @param authentication the current authentication; anonymous or {@code null} denies
   * @return {@code true} iff the caller may remove the member
   */
  public boolean canRemoveBereichRole(
      @NotNull UUID bereichId, @NotNull UUID userId, @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication)) {
      return false;
    }
    MembershipRole target = roleOf(userId, bereichId);
    if (target == MembershipRole.BEREICHSLEITER) {
      return callerIsPureOlMember();
    }
    if (target != null && target.isAreaRank()) {
      return callerHasRoleOnUnit(bereichId, MembershipRole.BEREICHSLEITER);
    }
    return false;
  }

  /**
   * Whether the caller is the Bereichsleiter of the SK's persisted parent Bereich and may thus
   * appoint or clear the SK lead.
   *
   * @param specialCommandId the Spezialkommando
   * @param authentication the current authentication; anonymous or {@code null} denies
   * @return {@code true} iff the caller leads the SK's parent Bereich
   */
  public boolean canAppointSkLead(
      @NotNull UUID specialCommandId, @Nullable Authentication authentication) {
    if (denyUnauthenticated(authentication)) {
      return false;
    }
    return callerIsBereichsleiterOfParent(specialCommandId);
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
   * {@code true} iff the calling principal holds exactly {@code rank} on its membership of {@code
   * orgUnitId}.
   *
   * @param orgUnitId the org unit to check the caller's rank on; never {@code null}.
   * @param rank the required rank; never {@code null}.
   * @return {@code true} iff the caller's membership of that org unit carries {@code rank}.
   */
  private boolean callerHasRoleOnUnit(@NotNull UUID orgUnitId, @NotNull MembershipRole rank) {
    return callerMemberships().stream()
        .anyMatch(m -> orgUnitId.equals(m.getId().getOrgUnitId()) && m.getRole() == rank);
  }

  /**
   * {@code true} iff the calling principal holds a pure {@link MembershipRole#OL_MEMBER} membership
   * (the only rank that confers org-wide appointment authority).
   *
   * @return {@code true} iff any of the caller's memberships is an OL member.
   */
  private boolean callerIsPureOlMember() {
    return callerMemberships().stream().anyMatch(m -> m.getRole() == MembershipRole.OL_MEMBER);
  }

  /**
   * The caller's membership rows, read once per HTTP request (REQ-DATA-003); read directly outside
   * a request.
   *
   * @return the caller's membership rows; empty for an anonymous caller
   */
  @NotNull
  private List<OrgUnitMembership> callerMemberships() {
    UUID callerId = authHelperService.currentUserId().orElse(null);
    if (callerId == null) {
      return List.of();
    }
    Map<UUID, List<OrgUnitMembership>> memo =
        RequestMemo.getIfBound(CALLER_MEMBERSHIPS, HashMap::new);
    if (memo == null) {
      return membershipRepository.findAllByIdUserId(callerId);
    }
    return memo.computeIfAbsent(
        callerId, id -> List.copyOf(membershipRepository.findAllByIdUserId(id)));
  }

  /**
   * Whether the caller is the {@link MembershipRole#BEREICHSLEITER} of the leaf unit's persisted
   * parent Bereich.
   *
   * @param leafUnitId the Staffel or SK whose parent Bereich gates the appointment
   * @return {@code true} iff the caller leads the leaf unit's parent Bereich
   */
  private boolean callerIsBereichsleiterOfParent(@NotNull UUID leafUnitId) {
    OrgUnit leaf = orgUnitRepository.findById(leafUnitId).orElse(null);
    if (leaf == null || leaf.getParent() == null) {
      return false;
    }
    OrgUnit parent = leaf.getParent();
    if (parent.getKind() != OrgUnitKind.BEREICH) {
      return false;
    }
    return callerHasRoleOnUnit(parent.getId(), MembershipRole.BEREICHSLEITER);
  }

  /**
   * The rank a user holds on an org unit, independent of the caller.
   *
   * @param userId the user
   * @param orgUnitId the org unit
   * @return the membership rank, or {@code null} when no membership exists
   */
  @Nullable
  private MembershipRole roleOf(@NotNull UUID userId, @NotNull UUID orgUnitId) {
    return membershipRepository
        .findById(new OrgUnitMembershipId(userId, orgUnitId))
        .map(OrgUnitMembership::getRole)
        .orElse(null);
  }
}
