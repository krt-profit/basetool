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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
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
 * <p>An external participant is editable only by an in-scope mission manager, officer or admin; a
 * linked participant by its user or an elevated role. Missing resources raise {@code
 * NotFoundException} instead of returning {@code false}.
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
   * Authorizes access to a single participant: elevated callers always, otherwise only the
   * participant's own linked user. An external participant is accessible to elevated callers only.
   *
   * <p>A missing participant raises {@link
   * de.greluc.krt.profit.basetool.backend.exception.NotFoundException}.
   */
  public boolean canAccessParticipant(
      UUID missionId, UUID participantId, Authentication authentication) {
    MissionParticipant p =
        Entities.require(
            missionParticipantRepository.findById(participantId), "Participant not found");

    if (!p.getMission().getId().equals(missionId)) {
      log.warn("Mission ID mismatch: {} != {}", p.getMission().getId(), missionId);
      return false;
    }

    if (p.getUser() == null) {
      return canManageMission(missionId, authentication);
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

    return canManageMission(missionId, authentication);
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
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the entry does
   *     not exist
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
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.ADMIN)));
    if (isAdmin) {
      return true;
    }

    boolean isOfficer =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    if (isOfficer && ownerScopeService.canEditMission(entry.getMission().getId())) {
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
  public boolean canManageMission(UUID missionId, Authentication authentication) {
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

    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(mission -> isOwnerOrManager(mission, authentication))
        .orElse(false);
  }

  /**
   * Authorizes adding or removing co-managers on a mission; same rule as {@link #canManageMission}.
   * Logs each check at debug level.
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
   * Authorizes changing a mission's owner: only the current owner, {@code ROLE_ADMIN} or {@code
   * ROLE_OFFICER}; co-managers and {@code ROLE_MISSION_MANAGER} may not.
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
    boolean isOfficer =
        reachable.stream().anyMatch(a -> a.getAuthority().equals(Roles.authority(Roles.OFFICER)));
    if (isOfficer && ownerScopeService.canEditMission(missionId)) {
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
   * Returns whether the calling user is the mission's owner or a co-manager.
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

    if (mission.getOwner() != null && mission.getOwner().getId().equals(userId)) {
      return true;
    }

    return mission.getManagers().stream().anyMatch(user -> user.getId().equals(userId));
  }
}
