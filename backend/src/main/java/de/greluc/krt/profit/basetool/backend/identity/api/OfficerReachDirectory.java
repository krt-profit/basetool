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

package de.greluc.krt.profit.basetool.backend.identity.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Whether an account holds officer reach, owned by identity and implemented by the org-unit module
 * (REQ-ROLE-002, ADR-0300).
 *
 * <p>The member-rank range depends on it, and identity sits below the module that knows the seats.
 */
public interface OfficerReachDirectory {

  /**
   * Whether the account holds officer reach in at least one org unit: a leadership seat held
   * together with the stored {@code KRT Member} role.
   *
   * @param userId the account to check; never {@code null}
   * @return {@code true} iff the account has a seat that confers reach and holds {@code KRT Member}
   */
  boolean holdsOfficerReach(@NotNull UUID userId);
}
