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

import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hangar module's access policy: the read and edit gates of a ship, evaluated on the scope
 * kernel (plan §5.4, ADR-0236).
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
}
