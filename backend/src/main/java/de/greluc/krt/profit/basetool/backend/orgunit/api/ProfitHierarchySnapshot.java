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

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Current designated Profit identity and active eligible units from one hierarchy read.
 *
 * @param profitRootId the unique active root, or null when the prerequisite is absent
 * @param eligibleUnitIds the active root and eligible active descendants
 */
public record ProfitHierarchySnapshot(@Nullable UUID profitRootId, Set<UUID> eligibleUnitIds) {

  /** Copies eligible identities so a caller cannot change a transaction's authority snapshot. */
  public ProfitHierarchySnapshot {
    eligibleUnitIds = Set.copyOf(eligibleUnitIds);
  }
}
