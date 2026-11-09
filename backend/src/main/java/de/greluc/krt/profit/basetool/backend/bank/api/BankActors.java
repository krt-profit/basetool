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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The one adapter that builds a {@link BankActor} from the database (ADR-0352): the roles stored in
 * {@code user_roles} with the role hierarchy applied, the org-unit memberships and the bank grants.
 * Must run inside a transaction.
 */
public interface BankActors {

  /**
   * The actor of the web path: every stored role counts.
   *
   * @param userId the member, or {@code null} when the request carries none
   * @return the member's actor; {@link BankActor#NOBODY} for {@code null} or an unknown id
   */
  @NotNull
  BankActor of(@Nullable UUID userId);

  /**
   * The actor of the exchange path: the stored {@code ADMIN} role is dropped before the hierarchy
   * applies, so an administrator acts only through their bank roles, grants and memberships
   * (REQ-XCH-009).
   *
   * @param userId the acting member
   * @return the member's actor without the Admin role; {@link BankActor#NOBODY} for an unknown id
   */
  @NotNull
  BankActor forExchange(@NotNull UUID userId);
}
