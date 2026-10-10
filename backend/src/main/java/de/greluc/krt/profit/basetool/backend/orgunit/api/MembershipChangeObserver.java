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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reacts to leadership and Kommandogruppe changes of the org-unit module inside the transaction
 * that makes them (plan §5.3, REQ-ROLE-006).
 *
 * <p>The org-unit services call it after each change and before their transaction commits; every
 * implementation joins that transaction ({@code MANDATORY}), so its writes commit or roll back with
 * the change.
 */
@ObserverSpi
public interface MembershipChangeObserver {

  /**
   * A member was appointed to a Bereich leadership role.
   *
   * @param bereichId the Bereich
   * @param userId the appointed account
   * @param role the granted role
   */
  void onBereichRoleGranted(
      @NotNull UUID bereichId, @NotNull UUID userId, @NotNull BereichLeadershipRole role);

  /**
   * A member joined an Organisationsleitung.
   *
   * @param organisationsleitungId the Organisationsleitung
   * @param userId the member's account
   */
  void onOlMemberAdded(@NotNull UUID organisationsleitungId, @NotNull UUID userId);

  /**
   * A member's SK-Leiter flag was set or cleared.
   *
   * @param specialCommandId the Spezialkommando
   * @param userId the member's account
   * @param isLead the new lead state
   */
  void onSkLeadChanged(@NotNull UUID specialCommandId, @NotNull UUID userId, boolean isLead);

  /**
   * A member was assigned a squadron rank.
   *
   * @param squadronId the Staffel
   * @param userId the member's account
   * @param rank the assigned squadron rank
   * @param group the bound Kommandogruppe, {@code null} for a Staffelleiter or an unbound Ensign
   */
  void onSquadronRankAssigned(
      @NotNull UUID squadronId,
      @NotNull UUID userId,
      @NotNull MembershipRole rank,
      @Nullable KommandoGroup group);

  /**
   * A member's squadron rank was cleared back to a plain membership.
   *
   * @param squadronId the Staffel
   * @param userId the member's account
   */
  void onSquadronRankCleared(@NotNull UUID squadronId, @NotNull UUID userId);

  /**
   * A membership in a Bereich, an Organisationsleitung or a Spezialkommando was removed.
   *
   * @param orgUnitId the org unit the membership pointed at
   * @param userId the member's account
   */
  void onUnitMembershipRemoved(@NotNull UUID orgUnitId, @NotNull UUID userId);

  /**
   * A Kommandogruppe was created.
   *
   * @param group the new group
   */
  void onKommandoGroupCreated(@NotNull KommandoGroup group);

  /**
   * A Kommandogruppe was renamed or reordered.
   *
   * @param group the updated group
   */
  void onKommandoGroupUpdated(@NotNull KommandoGroup group);

  /**
   * A Kommandogruppe was deleted.
   *
   * @param kommandoGroupId the deleted group's id
   */
  void onKommandoGroupDeleted(@NotNull UUID kommandoGroupId);
}
