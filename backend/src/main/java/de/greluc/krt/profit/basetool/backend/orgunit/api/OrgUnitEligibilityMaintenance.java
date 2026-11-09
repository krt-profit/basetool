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

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Dispatches affected-unit eligibility maintenance under the hierarchy change lock. */
@Service
@RequiredArgsConstructor
public class OrgUnitEligibilityMaintenance {

  private final OrgUnitRepository units;
  private final ObjectProvider<OrgUnitEligibilityObserver> observers;

  /**
   * Joins dependent maintenance for the changed unit's eligibility subtree.
   *
   * @param changed the managed unit after its organisational change
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void eligibilityChanged(@NotNull OrgUnit changed) {
    Set<UUID> affected = new LinkedHashSet<>();
    affected.add(changed.getId());
    if (changed.getKind() == OrgUnitKind.BEREICH) {
      affected.addAll(units.findChildOrgUnitIds(changed.getId()));
    }
    Set<UUID> affectedIds = Set.copyOf(affected);
    observers.orderedStream().forEach(observer -> observer.onEligibilityChanged(affectedIds));
  }
}
