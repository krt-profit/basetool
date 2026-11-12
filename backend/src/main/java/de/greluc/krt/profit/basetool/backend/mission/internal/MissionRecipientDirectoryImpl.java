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
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.MissionRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.MemberRoleCodes;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The mission module's {@link MissionRecipientDirectory}, reading participants and managers. */
@Service
@RequiredArgsConstructor
public class MissionRecipientDirectoryImpl implements MissionRecipientDirectory {

  private final MissionParticipantRepository missionParticipantRepository;
  private final MissionRepository missionRepository;
  private final UserRepository userRepository;
  private final OrgUnitMembershipRepository membershipRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;

  /**
   * The registered members signed up for a mission they may still read.
   *
   * @param missionId the mission
   * @param onlyNotCheckedIn {@code true} to keep only participants who have not checked in
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> participantsOf(@NotNull UUID missionId, boolean onlyNotCheckedIn) {
    Set<UUID> participants =
        onlyNotCheckedIn
            ? missionParticipantRepository.findNotCheckedInUserIdsByMission(missionId)
            : missionParticipantRepository.findRegisteredUserIdsByMission(missionId);
    if (participants.isEmpty()) {
      return Set.of();
    }
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(mission -> visibleMembers(mission, participants))
        .orElseGet(Set::of);
  }

  /**
   * Keeps active recipients who can open the mission without an active-unit pin.
   *
   * @param mission the mission and parent chain to inspect
   * @param candidates candidate recipient ids
   * @return the eligible subset
   */
  @NotNull
  Set<UUID> visibleMembers(@NotNull Mission mission, @NotNull Collection<UUID> candidates) {
    if (candidates.isEmpty()) {
      return Set.of();
    }
    List<User> activeUsers = userRepository.findActiveWithRolesByIds(candidates);
    if (activeUsers.isEmpty()) {
      return Set.of();
    }
    Set<UUID> internalUnits = new HashSet<>();
    for (Mission ancestor = mission; ancestor != null; ancestor = ancestor.getParent()) {
      if (Boolean.TRUE.equals(ancestor.getIsInternal()) && ancestor.getOwningOrgUnit() != null) {
        internalUnits.add(ancestor.getOwningOrgUnit().getId());
      }
    }
    Map<UUID, List<OrgUnitMembership>> memberships =
        membershipRepository
            .findAllOnActiveOrgUnitsByUserIds(activeUsers.stream().map(User::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(membership -> membership.getId().getUserId()));
    return activeUsers.stream()
        .filter(
            user -> mayRead(internalUnits, user, memberships.getOrDefault(user.getId(), List.of())))
        .map(User::getId)
        .collect(Collectors.toUnmodifiableSet());
  }

  private boolean mayRead(Set<UUID> internalUnits, User user, List<OrgUnitMembership> memberships) {
    Set<String> roles = MemberRoleCodes.of(user, memberships);
    if (Collections.disjoint(roles, Roles.MEMBER_OR_ABOVE)) {
      return false;
    }
    if (internalUnits.isEmpty() || roles.contains(Roles.ADMIN)) {
      return true;
    }
    if (!roles.contains(Roles.KRT_MEMBER)) {
      return false;
    }
    Set<UUID> reachable = orgUnitCascadeService.expandWithDescendants(memberships);
    if (reachable.containsAll(internalUnits)) {
      return true;
    }
    reachable.addAll(
        orgUnitCascadeService.grantedUnits(
            memberships,
            membership ->
                membership.isMissionManager()
                    || membership.getRole().confersOwnUnitOfficerReach()));
    return reachable.containsAll(internalUnits);
  }

  /**
   * The mission's owner and its co-managers.
   *
   * @param missionId the mission
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> leadershipOf(@NotNull UUID missionId) {
    Set<UUID> leadership = new HashSet<>(missionRepository.findManagerUserIdsById(missionId));
    missionRepository.findOwnerUserIdById(missionId).ifPresent(leadership::add);
    return leadership;
  }
}
