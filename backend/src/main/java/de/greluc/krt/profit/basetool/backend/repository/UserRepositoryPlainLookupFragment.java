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
 * Custom-repository fragment giving {@link UserRepository} a by-id lookup without the {@code roles}
 * entity graph (REQ-DATA-003).
 *
 * <p>For callers that need the user only as a foreign-key target or for a few scalars; it uses
 * {@link jakarta.persistence.EntityManager#find(Class, Object)} and never auto-flushes.
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
