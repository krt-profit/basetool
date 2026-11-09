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

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves actual functional unit writers for directed organisational notifications. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitWriterDirectory {

  private final OrgUnitMembershipRepository memberships;

  /**
   * Resolves current direct and covering contextual writers without a global administrator grant.
   *
   * @param unitIds the units whose writers are concretely affected
   * @return current writer account identities, deduplicated across units and roles
   */
  public Set<UUID> writerUserIds(@NotNull Collection<UUID> unitIds) {
    return unitIds.isEmpty() ? Set.of() : memberships.findCurrentWriterUserIdsForUnits(unitIds);
  }
}
