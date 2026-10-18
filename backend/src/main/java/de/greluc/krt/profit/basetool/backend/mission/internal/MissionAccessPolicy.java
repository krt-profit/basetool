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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.Permissions;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionScope;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The mission module's access policy: the read, edit, manage, owner-change, participant and
 * finance-entry gates of a mission, evaluated on the scope kernel (plan §5.4, ADR-0236).
 *
 * <p>The list-side half of the read rule is {@link MissionScope#PREDICATE}; both widen together.
 * {@link MissionEditor} asks {@link #canEdit} on the loaded mission; the remaining endpoints call
 * the id-based gates from SpEL as {@code @missionAccessPolicy}. Unknown ids are refused, a missing
 * participant or finance entry raises {@link
 * de.greluc.krt.profit.basetool.backend.kernel.NotFoundException}. Read-only transactional, so the
 * parent chain loads lazily.
 */
@Service(MissionAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MissionAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "missionAccessPolicy";

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelper;
  private final MissionRepository missionRepository;
  private final UserService userService;
  private final RoleHierarchy roleHierarchy;
  private final MissionParticipantRepository missionParticipantRepository;
  private final MissionFinanceEntryRepository missionFinanceEntryRepository;

  /**
   * Decides whether the caller passes {@code gate} on the already-loaded mission.
   *
   * @param mission the loaded mission the edit targets
   * @param gate the access the edit demands
   * @param authentication the caller, or {@code null} outside a request
   * @return {@code true} iff the caller passes the gate
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when a participant gate
   *     names an unknown participant
   */
  public boolean canEdit(
      @NotNull Mission mission,
      @NotNull MissionGate gate,
      @Nullable Authentication authentication) {
    return switch (gate) {
      case MissionGate.Manage _ -> canManageLoadedMission(mission, authentication);
      case MissionGate.See _ -> canSeeMission(mission);
      case MissionGate.Participant participant ->
          canAccessLoadedParticipant(mission, participant.participantId(), authentication);
    };
  }

  /**
   * Checks whether the caller may read mission {@code missionId}: the mission and every ancestor
   * must be readable, where a public mission is readable organisation-wide and an internal one only
   * within the owning org unit's read scope, or by a member-or-above when it has no owning unit
   * (REQ-ORG-009).
   *
   * @param missionId the mission to inspect; never {@code null}
   * @return {@code true} iff the caller may read the mission
   */
  public boolean canSeeMission(@NotNull UUID missionId) {
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(this::canSeeMissionChain)
        .orElse(false);
  }

  /**
   * {@link #canSeeMission(UUID)} for an already-loaded mission.
   *
   * @param mission the loaded mission
   * @return {@code true} iff the caller may read the mission and every ancestor
   */
  public boolean canSeeMission(@NotNull Mission mission) {
    return canSeeMissionChain(mission);
  }

  /**
   * Checks whether the caller may edit mission {@code missionId}: an ownerless mission passes and
   * leaves the decision to the manage gates, any other needs the owning org unit's edit scope,
   * without the public escape.
   *
   * @param missionId the mission to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the mission
   */
  public boolean canEditMission(@NotNull UUID missionId) {
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(
            m ->
                m.getOwningOrgUnit() == null
                    || ownerScopeService.canEditSquadron(m.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * Authorizes acting on one participant of an already-loaded mission: a linked participant's own
   * user, otherwise a caller who may manage the mission. An external participant is accessible to
   * managers only, and a participant of another mission to nobody.
   *
   * @param mission the loaded mission
   * @param participantId the participant the edit touches
   * @param authentication the caller, or {@code null} outside a request
   * @return {@code true} iff the caller may act on the participant
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the participant
   *     does not exist
   */
  public boolean canAccessLoadedParticipant(
      @NotNull Mission mission,
      @NotNull UUID participantId,
      @Nullable Authentication authentication) {
    MissionParticipant p =
        Entities.require(
            missionParticipantRepository.findById(participantId), "Participant not found");

    if (!p.getMission().getId().equals(mission.getId())) {
      log.warn("Mission ID mismatch: {} != {}", p.getMission().getId(), mission.getId());
      return false;
    }

    if (p.getUser() == null) {
      return canManageLoadedMission(mission, authentication);
    }

    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    if ("anonymousUser".equals(authentication.getPrincipal())) {
      return false;
    }

    UUID currentUserId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (currentUserId != null && p.getUser().getId().equals(currentUserId)) {
      return true;
    }

    return canManageLoadedMission(mission, authentication);
  }

  /**
   * Authorizes creating a mission finance entry: a caller who may manage the mission, or a member
   * booking against their own participant row on it (REQ-SEC-042).
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
   * Authorizes editing or deleting a mission finance entry: ADMIN always, an OFFICER within the
   * mission's org-unit scope, otherwise the entry's own user while still a participant of the
   * mission.
   *
   * @param entryId finance entry id
   * @param authentication current Spring Security authentication
   * @return true if the caller may edit the entry
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the entry does not
   *     exist
   */
  public boolean canEditFinanceEntry(UUID entryId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    MissionFinanceEntry entry =
        Entities.require(
            missionFinanceEntryRepository.findById(entryId), "Finance entry not found");

    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities());

    boolean isAdmin =
        reachable.stream().anyMatch(a -> Roles.authority(Roles.ADMIN).equals(a.getAuthority()));
    if (isAdmin) {
      return true;
    }

    boolean isOfficer =
        reachable.stream().anyMatch(a -> Roles.authority(Roles.OFFICER).equals(a.getAuthority()));
    if (isOfficer && canEditMission(entry.getMission().getId())) {
      return true;
    }

    UUID currentUserId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (currentUserId == null
        || entry.getParticipant().getUser() == null
        || !entry.getParticipant().getUser().getId().equals(currentUserId)) {
      return false;
    }

    return missionParticipantRepository
        .findByMissionIdAndUserId(entry.getMission().getId(), currentUserId)
        .isPresent();
  }

  /**
   * Authorizes management actions on a mission: elevated authorities (ADMIN, OFFICER,
   * MISSION_MANAGER) or the mission's owner or a co-manager.
   *
   * @param missionId mission id
   * @param authentication current Spring Security authentication
   * @return true if the caller may manage the mission
   */
  public boolean canManageMission(UUID missionId, @Nullable Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    if ("anonymousUser".equals(authentication.getPrincipal())) {
      return false;
    }

    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities());
    boolean isAdmin =
        reachable.stream().anyMatch(a -> Roles.authority(Roles.ADMIN).equals(a.getAuthority()));
    if (isAdmin) {
      return true;
    }

    if (hasElevatedMissionAuthority(reachable) && canEditMission(missionId)) {
      return true;
    }

    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(mission -> isOwnerOrManager(mission, authentication))
        .orElse(false);
  }

  /**
   * Authorizes changing a mission's owner: only the current owner, {@code ROLE_ADMIN} or {@code
   * ROLE_OFFICER}; co-managers and {@code ROLE_MISSION_MANAGER} may not.
   *
   * @param missionId mission id
   * @param authentication current Spring Security authentication
   * @return true if the caller may change the owner
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
        reachable.stream().anyMatch(a -> Roles.authority(Roles.ADMIN).equals(a.getAuthority()));
    if (isAdmin) {
      return true;
    }
    boolean isOfficer =
        reachable.stream().anyMatch(a -> Roles.authority(Roles.OFFICER).equals(a.getAuthority()));
    if (isOfficer && canEditMission(missionId)) {
      return true;
    }

    UUID userId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (userId == null) {
      return false;
    }
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(Mission::getOwner)
        .map(owner -> owner.getId().equals(userId))
        .orElse(false);
  }

  /**
   * {@link #canManageMission(UUID, Authentication)} for an already-loaded mission, without
   * reloading the aggregate into the persistence context.
   *
   * @param mission the already-loaded mission
   * @param authentication current Spring Security authentication, or {@code null}
   * @return {@code true} if the caller may manage this mission
   */
  public boolean canManageLoadedMission(
      @NotNull Mission mission, @Nullable Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    Collection<? extends GrantedAuthority> reachable =
        roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities());
    if (reachable.stream().anyMatch(a -> Roles.authority(Roles.ADMIN).equals(a.getAuthority()))) {
      return true;
    }
    if (hasElevatedMissionAuthority(reachable) && canEditMission(mission.getId())) {
      return true;
    }
    return isOwnerOrManager(mission, authentication);
  }

  /**
   * Returns whether the calling user is the mission's owner or a co-manager.
   *
   * @param mission already-loaded mission
   * @param authentication current Spring Security authentication
   * @return true if the user owns or co-manages the mission
   */
  public boolean isOwnerOrManager(@NotNull Mission mission, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }

    UUID userId = userService.getCurrentUser().map(User::getId).orElse(null);
    if (userId == null) {
      return false;
    }

    if (mission.getOwner() != null && mission.getOwner().getId().equals(userId)) {
      return true;
    }

    return mission.getManagers().stream().anyMatch(user -> user.getId().equals(userId));
  }

  private static boolean hasElevatedMissionAuthority(
      @NotNull Collection<? extends GrantedAuthority> reachable) {
    return reachable.stream()
        .anyMatch(
            a ->
                Roles.authority(Roles.MISSION_MANAGER).equals(a.getAuthority())
                    || Roles.MISSION_MANAGER.equals(a.getAuthority())
                    || Permissions.MISSION_MANAGE.equals(a.getAuthority())
                    || Roles.authority(Roles.OFFICER).equals(a.getAuthority()));
  }

  private boolean canSeeMissionChain(@NotNull Mission mission) {
    for (Mission ancestor = mission; ancestor != null; ancestor = ancestor.getParent()) {
      if (!canSeeMissionRow(ancestor)) {
        return false;
      }
    }
    return true;
  }

  private boolean canSeeMissionRow(@NotNull Mission mission) {
    boolean internal = Boolean.TRUE.equals(mission.getIsInternal());
    if (mission.getOwningOrgUnit() == null) {
      return !internal || authHelper.isMemberOrAbove();
    }
    return !internal || ownerScopeService.canSeeSquadron(mission.getOwningOrgUnit().getId());
  }
}
