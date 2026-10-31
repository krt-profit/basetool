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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipScope;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hangar module's access policy: the read and edit gates of a ship, evaluated on the scope
 * kernel (plan §5.4, ADR-0236).
 *
 * <p>The list-side half of the rule is {@link ShipScope#TRIPLE}; both widen together.
 *
 * <p>Invoked from SpEL as {@code @hangarAccessPolicy}. Unknown ids are refused. Read-only
 * transactional.
 */
@Service(HangarAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HangarAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "hangarAccessPolicy";

  private final OwnerScopeService ownerScopeService;
  private final ShipRepository shipRepository;

  /**
   * Checks whether the caller may read ship {@code shipId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then the org-unit read scope.
   *
   * @param shipId the ship to inspect; never {@code null}
   * @return {@code true} iff the caller may read the ship
   */
  public boolean canSeeShip(@NotNull UUID shipId) {
    return shipRepository
        .findById(shipId)
        .map(s -> ownerScopeService.permitsOwnedRow(s.getOwner(), s.getOwningOrgUnit(), false))
        .orElse(false);
  }

  /**
   * Checks whether the caller may edit ship {@code shipId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then the org-unit edit scope.
   *
   * @param shipId the ship to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the ship
   */
  public boolean canEditShip(@NotNull UUID shipId) {
    return shipRepository
        .findById(shipId)
        .map(s -> ownerScopeService.permitsOwnedRow(s.getOwner(), s.getOwningOrgUnit(), true))
        .orElse(false);
  }

  /**
   * Checks whether the ships of org unit {@code orgUnitId} appear in the caller's unit overview
   * (REQ-HANGAR-003), the read behind the unit's live-sync room.
   *
   * @param orgUnitId the org unit to inspect; never {@code null}
   * @return {@code true} iff the caller's unit-overview scope admits the unit
   */
  public boolean canSeeUnitHangar(@NotNull UUID orgUnitId) {
    return ownerScopeService.currentUnitOverviewScope().permits(orgUnitId);
  }

  /**
   * Checks whether the unit overview offers per-ship owner details in the caller's active context:
   * officer reach in the pinned unit, or in any unit when none is pinned (REQ-HANGAR-001). The
   * service narrows the details to the ships of units the caller reaches.
   *
   * @return {@code true} iff the caller is an admin or holds officer reach in the active context
   */
  public boolean canSeeOwnerDetails() {
    return ownerScopeService.canActAsOfficerInActiveContext();
  }

  /**
   * Checks whether the caller may run the bulk reset of the {@code fitted} flag in the active
   * context; the reset itself only touches ships of units the caller reaches (REQ-HANGAR-004).
   *
   * @return {@code true} iff the caller is an admin or holds officer reach in the active context
   */
  public boolean canResetFitted() {
    return ownerScopeService.canActAsOfficerInActiveContext();
  }
}
