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
 * SpEL-level authorisation for the delegated appointment ladder (epic #800, REQ-ROLE-004). It
 * answers "may this <em>non-admin</em> caller appoint / change / revoke rank R on org unit X?".
 * Admin is handled separately by the {@code hasRole('ADMIN')
 * or @orgRoleManagementSecurityService.canX(...)} shape at each endpoint — so the verdicts here
 * deliberately <strong>never</strong> consult {@code isAdmin()}; an admin never reaches these
 * methods for the deciding answer.
 *
 * <p>The ladder is a strictly-higher-tier rule, which makes self-promotion structurally impossible
 * (to grant rank R you must hold a strictly-higher rank, which you cannot grant yourself):
 *
 * <ul>
 *   <li>pure {@code OL_MEMBER} → appoints / removes a Bereichsleiter (any Bereich);
 *   <li>{@code BEREICHSLEITER} of a Bereich → appoints Koordinator / Operator on <em>that</em>
 *       Bereich, and the Staffelleiter / SK-Lead of its child Staffeln / SKs;
 *   <li>{@code STAFFELLEITER} of a squadron → appoints Kommandoleiter / stellv. / Ensign and
 *       manages the Kommandogruppen of <em>that</em> squadron;
 *   <li>appointing an {@code OL_MEMBER} has no rung — it is admin-only (no method here).
 * </ul>
 *
 * <p>The verdict is computed from the caller's own membership ranks only (resolved via {@link
 * AuthHelperService#currentUserId()}); it never reads the admin-pin header, contextual authorities,
 * or {@link OwnerScopeService}. The parent Bereich of a Staffel/SK is always derived from the
 * target unit's persisted {@code parent} edge — never a request-supplied id — closing the
 * cross-Bereich escalation hole. The absence of an {@code OwnerScopeService} dependency is
 * ArchUnit-pinned.
 *
 * <p>Class-level {@code @Transactional(readOnly = true)} matches the other authorisation beans.
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
   * Whether the caller may assign the given squadron rank on the squadron: a {@code STAFFELLEITER}
   * appointment needs the Bereichsleiter of the squadron's parent Bereich; a Kommandoleiter /
   * stellv. / Ensign appointment needs the Staffelleiter of the squadron itself (no
   * self-promotion).
   *
   * @param squadronId the squadron whose rank is being assigned; never {@code null}.
   * @param rank the squadron rank to assign; non-squadron ranks always deny.
   * @param authentication the current authentication; anonymous / {@code null} denies.
   * @return {@code true} iff the delegated caller may make this assignment.
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
   * Whether the caller may clear the target member's current squadron rank: routed by that rank —
   * removing a Staffelleiter needs the parent Bereichsleiter, removing a Kommandoleiter / stellv. /
   * Ensign needs the squadron's Staffelleiter.
   *
   * @param squadronId the squadron; never {@code null}.
   * @param userId the member whose rank is being cleared; never {@code null}.
   * @param authentication the current authentication; anonymous / {@code null} denies.
   * @return {@code true} iff the delegated caller may clear the member's rank.
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
   * Whether the caller may update / delete the given Kommandogruppe — the Staffelleiter of the
   * group's squadron (the squadron is read from the group's persisted edge, never a request value).
   *
   * @param groupId the Kommandogruppe; never {@code null}.
   * @param authentication the current authentication; anonymous / {@code null} denies.
   * @return {@code true} iff the caller is the Staffelleiter of the group's squadron.
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
   * Whether the caller may grant the given Bereich role on the Bereich: a Bereichsleiter
   * appointment needs a pure OL member; a Koordinator / Operator appointment needs the
   * Bereichsleiter of that Bereich (no self-promotion to Bereichsleiter).
   *
   * @param bereichId the Bereich; never {@code null}.
   * @param role the Bereich role being granted; never {@code null}.
   * @param authentication the current authentication; anonymous / {@code null} denies.
   * @return {@code true} iff the delegated caller may grant the role.
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
   * Whether the caller may remove the target member's Bereich membership: routed by the target's
   * current rank — removing a Bereichsleiter needs a pure OL member, removing a Koordinator /
   * Operator needs that Bereich's Bereichsleiter.
   *
   * @param bereichId the Bereich; never {@code null}.
   * @param userId the member to remove; never {@code null}.
   * @param authentication the current authentication; anonymous / {@code null} denies.
   * @return {@code true} iff the delegated caller may remove the member.
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
   * Whether the caller may appoint or clear the SK lead — the Bereichsleiter of the SK's parent
   * Bereich (derived from the SK's persisted parent edge).
   *
   * @param specialCommandId the Spezialkommando; never {@code null}.
   * @param authentication the current authentication; anonymous / {@code null} denies.
   * @return {@code true} iff the caller is the Bereichsleiter of the SK's parent Bereich.
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
   * The calling principal's membership rows, read once per HTTP request (REQ-DATA-003, BE-PERF-15).
   * Every verdict here is a question about the caller's own ranks, and the Leitung view asks it for
   * every Bereich, Staffel and Spezialkommando in turn — one {@code findAllByIdUserId} per request
   * instead of a lookup per unit and verdict. Keyed by caller id, so a request cannot read another
   * principal's rows. Outside an HTTP request there is no memo and the rows are read directly. Like
   * the other request memos, it assumes the caller's own ranks do not change within the request
   * that asks.
   *
   * @return the caller's membership rows; empty for an anonymous caller. Never {@code null}.
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
   * {@code true} iff the caller is the {@link MembershipRole#BEREICHSLEITER} of the leaf unit's
   * parent Bereich — derived from the persisted parent edge of {@code leafUnitId}, never a
   * request-supplied id, so a caller cannot claim a foreign Bereich.
   *
   * @param leafUnitId the Staffel / SK whose parent Bereich gates the appointment; never {@code
   *     null}.
   * @return {@code true} iff the caller leads the leaf unit's parent Bereich.
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
   * The caller-independent current rank a user holds on an org unit, or {@code null} if no
   * membership exists.
   *
   * @param userId the user; never {@code null}.
   * @param orgUnitId the org unit; never {@code null}.
   * @return the membership rank, or {@code null}.
   */
  @Nullable
  private MembershipRole roleOf(@NotNull UUID userId, @NotNull UUID orgUnitId) {
    return membershipRepository
        .findById(new OrgUnitMembershipId(userId, orgUnitId))
        .map(OrgUnitMembership::getRole)
        .orElse(null);
  }
}
