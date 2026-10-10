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

import de.greluc.krt.profit.basetool.backend.notification.api.OrgUnitRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/** The orgunit module's {@link OrgUnitRecipientDirectory}, reading the membership flags. */
@Service
@RequiredArgsConstructor
public class OrgUnitMembershipRecipientDirectory implements OrgUnitRecipientDirectory {

  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

  /**
   * The members flagged as Lead of an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> leadsOf(@NotNull UUID orgUnitId) {
    return orgUnitMembershipRepository.findLeadUserIdsByOrgUnit(orgUnitId);
  }

  /**
   * The members flagged as Logistician of an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> logisticiansOf(@NotNull UUID orgUnitId) {
    return orgUnitMembershipRepository.findLogisticianUserIdsByOrgUnit(orgUnitId);
  }

  /**
   * The members flagged as Mission Manager of an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> missionManagersOf(@NotNull UUID orgUnitId) {
    return orgUnitMembershipRepository.findMissionManagerUserIdsByOrgUnit(orgUnitId);
  }

  /**
   * The members whose rank is a leadership seat of an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> leadershipOf(@NotNull UUID orgUnitId) {
    return orgUnitMembershipRepository.findLeadershipUserIdsByOrgUnit(orgUnitId);
  }
}
