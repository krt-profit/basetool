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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom-repository fragment giving {@link UserRepository} a graph-free by-id lookup (BE-PERF-12,
 * REQ-DATA-003).
 *
 * <p>The overridden {@link UserRepository#findById(UUID)} graphs {@code roles} <em>and</em> {@code
 * roles.permissions} — right for the authentication path and the caller's own {@code /users/me},
 * which assemble authorities from both, and pure waste for the many service methods that only need
 * the user as a foreign-key target ({@code ship.owner}, an inventory owner filter, a new membership
 * row) or read a scalar or two (the effective name, the approval status). Those resolve the user
 * here instead, through {@link jakarta.persistence.EntityManager#find(Class, Object)}: the plain
 * row with no collection fetched, first-level-cache aware (a user the transaction already holds
 * costs no statement at all), and never auto-flushing, unlike a JPQL query. A caller that does
 * touch {@code getRoles()} afterwards still gets them, lazily and inside its own transaction.
 */
public interface UserRepositoryPlainLookupFragment {

  /**
   * Loads a user by id without the role graph, via {@code EntityManager.find}.
   *
   * @param id the user id; must not be {@code null}
   * @return the user, or empty when none exists
   */
  Optional<User> findPlainById(UUID id);
}
