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

package de.greluc.krt.profit.basetool.backend.orgunit.internal;

import de.greluc.krt.profit.basetool.backend.notification.api.OrgUnitRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitSeatEligibility;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The orgunit module's {@link OrgUnitRecipientDirectory}, reading the membership ranks and flags on
 * active org units and keeping only the members who hold {@code KRT Member}, since without it, or
 * on a deactivated unit, no membership confers anything (REQ-SEC-015, REQ-NOTIF-024, REQ-ORG-032).
 */
@Service
@RequiredArgsConstructor
public class OrgUnitMembershipRecipientDirectory implements OrgUnitRecipientDirectory {

  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitSeatEligibility seatEligibility;

  /**
   * The members flagged as Lead of an org unit who hold {@code KRT Member}.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> leadsOf(@NotNull UUID orgUnitId) {
    return seatEligibility.krtMembersAmong(
        orgUnitMembershipRepository.findLeadUserIdsByOrgUnit(orgUnitId));
  }

  /**
   * The members flagged as Logistician of an org unit who hold {@code KRT Member}.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> logisticiansOf(@NotNull UUID orgUnitId) {
    return seatEligibility.krtMembersAmong(
        orgUnitMembershipRepository.findLogisticianUserIdsByOrgUnit(orgUnitId));
  }

  /**
   * The members flagged as Mission Manager of an org unit who hold {@code KRT Member}.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> missionManagersOf(@NotNull UUID orgUnitId) {
    return seatEligibility.krtMembersAmong(
        orgUnitMembershipRepository.findMissionManagerUserIdsByOrgUnit(orgUnitId));
  }

  /**
   * The members whose rank is a leadership seat of an org unit and who hold {@code KRT Member}.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> leadershipOf(@NotNull UUID orgUnitId) {
    return seatEligibility.krtMembersAmong(
        orgUnitMembershipRepository.findLeadershipUserIdsByOrgUnit(orgUnitId));
  }

  /**
   * The members who hold a leadership seat in any org unit and {@code KRT Member}.
   *
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> allSeatHolders() {
    return seatEligibility.krtMembersAmong(orgUnitMembershipRepository.findAllLeadershipUserIds());
  }
}
