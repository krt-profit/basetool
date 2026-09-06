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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization helper for mission-scoped {@code @PreAuthorize} expressions.
 *
 * <p>Methods on this bean are referenced from {@code @PreAuthorize} on controllers and other
 * services (e.g. {@code @missionSecurityService.canEditFinanceEntry(#id, authentication)}). Each
 * method translates a "can the caller do X on resource Y" question into a boolean by combining the
 * caller's authorities with the resource's owner/manager relations. An <em>external</em>
 * participant (unlinked, no user account) is editable by a mission manager / officer / admin in
 * scope and by nobody else; a participant linked to a user is editable only by that user or an
 * elevated role.
 *
 * <p>Missing resources translate to {@code NotFoundException} rather than {@code false} so a stale
 * frontend gets a deterministic 404 instead of an opaque "access denied" for an entity that no
 * longer exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MissionSecurityService {

  private final MissionRepository missionRepository;
  private final UserService userService;
  private final RoleHierarchy roleHierarchy;
  private final MissionParticipantRepository missionParticipantRepository;
  private final MissionFinanceEntryRepository missionFinanceEntryRepository;
  private final OwnerScopeService ownerScopeService;

  /**
   * Authorizes access to a single participant of a mission.
   *
   * <p>Access is granted when the caller has elevated privileges (MISSION_MANAGER / OFFICER / ADMIN
   * / mission owner or manager) OR when the participant belongs to the currently authenticated user
   * (Self-Edit: {@code participant.user.id == jwt.sub}). An <em>external</em> (unlinked)
   * participant is editable by an elevated caller only — the row carries no creator to bind a
   * self-edit to (ADR-0159, decision D4).
   *
   * <p>If the participant does not exist (e.g. the frontend holds a stale row whose entry was
   * concurrently deleted in another tab), this method translates the missing row into a {@code 404
   * Not Found} via {@link de.greluc.krt.profit.basetool.backend.exception.NotFoundException}
   * instead of letting a plain {@link RuntimeException} bubble up as a generic {@code 500 Internal
   * Server Error} (see RFC7807 Problem Details).
   */
  public boolean canAccessParticipant(
      UUID missionId, UUID participantId, Authentication authentication) {
    MissionParticipant p =
        missionParticipantRepository
            .findById(participantId)
            .orElseThrow(() -> new NotFoundException("Participant not found"));

    if (!p.getMission().getId().equals(missionId)) {
      log.warn("Mission ID mismatch: {} != {}", p.getMission().getId(), missionId);
      return false;
    }

    if (p.getUser() == null) {
      // An EXTERNAL row — a named person without an account, recorded by somebody else. Editing
      // and removing it is the mission leadership's (ADR-0159, decision D4), because the row
      // carries no creator to bind a self-edit to: there is no `user` to compare a subject
      // against, and the id alone proves nothing (the roster exposes participant ids to every
      // member who can see the mission).
      //
      // This branch used to accept a per-row capability token (REQ-SEC-018, header
      // `X-Guest-Edit-Token`) so the anonymous creator of a guest sign-up could edit their own
      // row. There is no anonymous sign-up left to mint one for, and V239 dropped the column that
      // stored its hash. Note what the token needed alongside it to be safe: a `canSeeMission`
      // re-check, because the capability otherwise outlived the surface that granted it — a guest
      // who signed up while the mission was public kept PUT / DELETE / check-in after it was
      // flipped to internal or reached COMPLETED, and back-dating a settled operation moved real
      // money away from every other participant. `canManageMission` carries that scope check
      // inherently, so nothing is lost by the simplification.
      return canManageMission(missionId, authentication);
    }

    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    // Handle anonymous authentication correctly
    if ("anonymousUser".equals(authentication.getPrincipal())) {
      return false;
    }

    // Self-edit first: the participant's own linked user may always manage their row. Checked
    // before
    // the scope gate so a member editing their own participation never needs mission-management
    // rights — and so the common self-edit path does not load the mission aggregate at all.
    UUID currentUserId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (currentUserId != null && p.getUser().getId().equals(currentUserId)) {
      return true;
    }

    // Managing ANOTHER user's participant row is a mission write, so it must pass the same
    // owning-OrgUnit scope gate as every other mission write: canManageMission only admits an
    // elevated mission role (MISSION_MANAGER / OFFICER) when ownerScopeService.canEditMission also
    // passes, plus ADMIN and the mission owner / co-managers. An earlier version instead
    // short-circuited on the bare ROLE_MISSION_MANAGER authority, which CustomJwtGrantedAuthorities
    // Converter grants as the OR-union over ALL of a caller's memberships — letting a mission
    // manager
    // of squadron A check in/out, remove, or flip the payout preference of participants on squadron
    // B's internal missions (security audit AUTHZ-1; REQ-ORG-009 / MULTI_SQUADRON_PLAN.md section
    // 1:
    // editing is the owning OrgUnit's prerogative).
    return canManageMission(missionId, authentication);
  }

  /**
   * Authorizes <b>creating</b> a mission finance entry, as the write-level twin of {@link
   * #canEditFinanceEntry}.
   *
   * <p>True for a caller who may manage the mission ({@link #canManageMission} — ADMIN
   * unconditionally, an OFFICER / MISSION_MANAGER whose owning-OrgUnit scope covers it, the owner
   * or a co-manager), and otherwise only for a member booking against <b>their own</b> participant
   * row on that mission. The self-booking branch resolves the caller's participant row by {@code
   * (missionId, userId)} and compares it to the requested id, so it enforces all three conditions
   * at once: the row exists, it belongs to this mission, and it is the caller's.
   *
   * <p><b>REQ-SEC-042 — why this replaced the read-level gate.</b> The create used to be gated by
   * {@code ownerScopeService.canSeeMission(...)}, which deliberately grants the cross-squadron
   * <em>public escape</em> on a non-internal mission — appropriate for a read, wrong for a write.
   * Combined with a service that only checked that the participant belonged to the mission, any
   * member could book income/expense rows into another squadron's payout ledger and attribute them
   * to a member of that squadron, while the edit/delete of the very same row stayed restricted to
   * its owner / an officer in scope. A create strictly weaker than the edit of what it creates is
   * the broken-object-level-authorization asymmetry this closes; booking money is a management act
   * on the mission, so it is gated like one (MULTI_SQUADRON_PLAN.md section 1: editing is the
   * owning OrgUnit's prerogative).
   *
   * @param missionId the mission the entry is booked against
   * @param participantId the participant the entry is attributed to
   * @param authentication current Spring Security authentication
   * @return true if the caller may create the entry
   */
  public boolean canCreateFinanceEntry(
      UUID missionId, UUID participantId, Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || missionId == null
        || participantId == null) {
      return false;
    }

    if (canManageMission(missionId, authentication)) {
      return true;
    }

    UUID currentUserId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (currentUserId == null) {
      return false;
    }

    return missionParticipantRepository
        .findByMissionIdAndUserId(missionId, currentUserId)
        .map(own -> participantId.equals(own.getId()))
        .orElse(false);
  }

  /**
   * Authorizes editing or deleting a mission finance entry.
   *
   * <p>Grants access to ADMIN unconditionally and to an OFFICER only when the entry's mission is
   * within the officer's owning-OrgUnit scope ({@link OwnerScopeService#canEditMission(UUID)} —
   * security audit H1, mirroring {@link #canManageMission}/{@link #canChangeOwner}); otherwise the
   * entry's linked participant must belong to the calling user AND the user must currently be a
   * registered participant of the same mission. The "still a participant" check prevents a former
   * participant from editing their finance entries after they've been removed from the mission.
   *
   * @param entryId finance entry id
   * @param authentication current Spring Security authentication
   * @return true if the caller may edit the entry
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the entry does
   *     not exist
   */
  public boolean canEditFinanceEntry(UUID entryId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    MissionFinanceEntry entry =
        missionFinanceEntryRepository
            .findById(entryId)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "Finance entry not found"));

    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities());

    // ADMIN bypasses every gate (system-wide oversight across squadrons; see
    // MULTI_SQUADRON_PLAN.md section 1).
    boolean isAdmin =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.ADMIN)));
    if (isAdmin) {
      return true;
    }

    // Security audit H1: an OFFICER may edit/delete a finance entry ONLY of a mission within their
    // own owning-OrgUnit scope. ROLE_OFFICER is a flat, cross-squadron realm authority, so without
    // the additional ownerScopeService.canEditMission gate a bare officer could mutate the payout
    // ledger of another squadron's (even internal) mission — the exact cross-tenant write the
    // sibling mission-write gates (canManageMission line 219, canManageManagers line 272,
    // canChangeOwner line 324) were already hardened against under audit AUTHZ-1
    // (MULTI_SQUADRON_PLAN.md section 1: editing is the owning OrgUnit's prerogative). This was the
    // one mission write left with the global-OFFICER short-circuit.
    boolean isOfficer =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    if (isOfficer && ownerScopeService.canEditMission(entry.getMission().getId())) {
      return true;
    }

    // Must be the owner of the entry (if the participant has a linked user account)
    UUID currentUserId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (currentUserId == null
        || entry.getParticipant().getUser() == null
        || !entry.getParticipant().getUser().getId().equals(currentUserId)) {
      return false;
    }

    // Must be a registered participant of this mission
    return missionParticipantRepository
        .findByMissionIdAndUserId(entry.getMission().getId(), currentUserId)
        .isPresent();
  }

  /**
   * Authorizes any management action on a mission (edit, add/remove participant, …). True when the
   * caller carries one of the elevated authorities (via the role hierarchy: ADMIN, OFFICER,
   * MISSION_MANAGER, plus the legacy non-{@code ROLE_}-prefixed equivalents) or is the mission's
   * owner / a listed co-manager.
   *
   * @param missionId mission id
   * @param authentication current Spring Security authentication
   * @return true if the caller may manage the mission
   */
  public boolean canManageMission(UUID missionId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    // An AnonymousAuthenticationToken IS authenticated and carries ROLE_ANONYMOUS, so the check
    // above does not catch it — every sibling gate in this class spells the principal test out for
    // that reason (canChangeOwner:422, canAccessParticipant). This one did not, and it is now the
    // gate an external participant row hangs off (ADR-0159, D4). Nothing reaches it anonymously
    // today because the URL matrix refuses first, but a gate that depends on a matrix entry
    // elsewhere being right is one deletion away from being wrong.
    if ("anonymousUser".equals(authentication.getPrincipal())) {
      return false;
    }

    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities());
    boolean isAdmin =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.ADMIN)));
    // ROLE_ADMIN bypasses every gate (admin always sees / edits across squadrons; see
    // MULTI_SQUADRON_PLAN.md section 1).
    if (isAdmin) {
      return true;
    }

    // Elevated mission roles (MISSION_MANAGER, OFFICER, MISSION_MANAGE) need to ADDITIONALLY
    // pass the squadron-scope check on the target mission — otherwise an Officer or
    // Mission-Manager from squadron A could edit missions of squadron B
    // (MULTI_SQUADRON_PLAN.md section 1: editing is the owning squadron's prerogative).
    boolean hasElevatedMissionAuthority =
        reachable.stream()
            .anyMatch(
                a ->
                    a.getAuthority().equals(Roles.authority(Roles.MISSION_MANAGER))
                        || a.getAuthority().equals(Roles.MISSION_MANAGER)
                        || a.getAuthority().equals(Permissions.MISSION_MANAGE)
                        || a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    if (hasElevatedMissionAuthority && ownerScopeService.canEditMission(missionId)) {
      return true;
    }

    // Owner/manager fall-through reads only owner + managers, never the roster: use the
    // via em.find so the gate loads no roster and never auto-flushes (#1139).
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(mission -> isOwnerOrManager(mission, authentication))
        .orElse(false);
  }

  /**
   * Authorizes adding/removing co-managers on a mission. Same elevated-authority surface as {@link
   * #canManageMission} plus the mission owner / current co-managers. Verbose debug-level trace
   * lines exist because this is the most common "why was I denied" report — enable {@code DEBUG} on
   * this class to see exactly which authority check passed or failed for a given user.
   *
   * @param missionId mission id
   * @param authentication current Spring Security authentication
   * @return true if the caller may edit the manager list
   */
  public boolean canManageManagers(UUID missionId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      log.debug("Authentication failed or missing for canManageManagers on mission {}", missionId);
      return false;
    }

    Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authorities);
    log.debug(
        "User {} authorities: {}, Reachable: {}", authentication.getName(), authorities, reachable);

    boolean isAdmin =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.ADMIN)));
    if (isAdmin) {
      log.debug(
          "Access granted for user {} via ROLE_ADMIN for mission {}",
          authentication.getName(),
          missionId);
      return true;
    }

    // Elevated mission roles need an additional squadron-scope check before they may edit the
    // manager list of a mission that does not belong to their squadron
    // (MULTI_SQUADRON_PLAN.md section 1).
    boolean hasElevatedAuthority =
        reachable.stream()
            .anyMatch(
                a ->
                    a.getAuthority().equals(Roles.authority(Roles.MISSION_MANAGER))
                        || a.getAuthority().equals(Roles.MISSION_MANAGER)
                        || a.getAuthority().equals(Permissions.MISSION_MANAGE)
                        || a.getAuthority().equals(Roles.authority(Roles.OFFICER)));

    if (hasElevatedAuthority && ownerScopeService.canEditMission(missionId)) {
      log.debug(
          "Access granted for user {} via elevated authority + squadron scope for mission {}",
          authentication.getName(),
          missionId);
      return true;
    }

    // Owner/manager fall-through reads only owner + managers via em.find (#1139): no roster load.
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(
            mission -> {
              boolean result = isOwnerOrManager(mission, authentication);
              log.debug(
                  "Access check for user {} on mission {} (owner/manager): {}",
                  authentication.getName(),
                  missionId,
                  result);
              return result;
            })
        .orElseGet(
            () -> {
              log.debug("Mission {} not found for canManageManagers check", missionId);
              return false;
            });
  }

  /**
   * Authorizes changing the owner of a mission. Tighter than {@link #canManageManagers(UUID,
   * Authentication)}: only the current owner of the mission or holders of the global {@code
   * ROLE_ADMIN} / {@code ROLE_OFFICER} authorities may transfer ownership. Regular co-managers and
   * holders of the mission-scoped role {@code ROLE_MISSION_MANAGER} are NOT permitted to change the
   * owner, since they would otherwise be able to displace the original owner and grant themselves
   * ownership of any mission they have manager rights on.
   */
  public boolean canChangeOwner(UUID missionId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    if ("anonymousUser".equals(authentication.getPrincipal())) {
      return false;
    }

    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities());
    boolean isAdmin =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.ADMIN)));
    if (isAdmin) {
      return true;
    }
    // Officer: same squadron-scope gate as canManageMission. Without it an officer from
    // squadron A could transfer ownership of squadron B's missions.
    boolean isOfficer =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    if (isOfficer && ownerScopeService.canEditMission(missionId)) {
      return true;
    }

    UUID userId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (userId == null) {
      return false;
    }
    // Owner check reads only owner via em.find (#1139): no roster load.
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(Mission::getOwner)
        .map(owner -> owner.getId().equals(userId))
        .orElse(false);
  }

  /**
   * {@link #canManageMission(UUID, Authentication)} for a mission the caller <em>already
   * holds</em>, with no second load of the aggregate.
   *
   * <p>The id-taking variant re-reads the mission through {@code findByIdForAuthorization}. Calling
   * it from a controller, ahead of the writing service's own {@code findById}, put a second copy of
   * the aggregate into the open-session persistence context and the subsequent write then compared
   * against a stale participant version - a spurious {@code 409} on an edit nobody else had
   * touched. That is the #1139 hazard the scope loads were reshaped to avoid, and it is why the
   * payload-level "may this caller manage the mission" question is answered inside the service,
   * from the entity it has just loaded, rather than at the HTTP boundary.
   *
   * @param mission the already-loaded mission.
   * @param authentication current Spring Security authentication.
   * @return {@code true} if the caller may manage this mission.
   */
  public boolean canManageLoadedMission(Mission mission, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities());
    if (reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.ADMIN)))) {
      return true;
    }
    boolean hasElevatedMissionAuthority =
        reachable.stream()
            .anyMatch(
                a ->
                    a.getAuthority().equals(Roles.authority(Roles.MISSION_MANAGER))
                        || a.getAuthority().equals(Roles.MISSION_MANAGER)
                        || a.getAuthority().equals(Permissions.MISSION_MANAGE)
                        || a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    if (hasElevatedMissionAuthority && ownerScopeService.canEditMission(mission.getId())) {
      return true;
    }
    return isOwnerOrManager(mission, authentication);
  }

  /**
   * Returns true if the calling user is the mission's owner or appears in its manager list. Public
   * helper because both {@code canManage*} methods need the same check and a private variant would
   * be untestable in isolation.
   *
   * @param mission already-loaded mission
   * @param authentication current Spring Security authentication
   * @return true if the user owns or co-manages the mission
   */
  public boolean isOwnerOrManager(Mission mission, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    UUID userId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (userId == null) {
      return false;
    }

    // Check if user is owner
    if (mission.getOwner() != null && mission.getOwner().getId().equals(userId)) {
      return true;
    }

    // Check if user is in managers list
    return mission.getManagers().stream().anyMatch(user -> user.getId().equals(userId));
  }
}
