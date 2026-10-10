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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether the caller may book stock into a member's Lager rows (REQ-SEC-005, APPSEC-01);
 * implemented by the inventory module's access policy and asked by every module that books stock in
 * on a member's behalf.
 */
public interface BookInPolicy {

  /**
   * Checks whether the caller may book stock in under {@code ownerUserId}: an admin, the member
   * themselves, or a caller whose edit scope covers any of the member's memberships. The per-row
   * bound is the stamp validation.
   *
   * @param ownerUserId the member whose Lager would receive the stock
   * @return {@code true} iff the caller may book stock in under that member
   */
  boolean mayBookInFor(@NotNull UUID ownerUserId);
}
