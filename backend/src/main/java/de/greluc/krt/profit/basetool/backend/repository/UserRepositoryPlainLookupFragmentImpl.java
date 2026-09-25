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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * {@code EntityManager}-backed implementation of {@link UserRepositoryPlainLookupFragment}, picked
 * up by Spring Data through the {@code Impl} suffix and mixed into {@link UserRepository}.
 */
public class UserRepositoryPlainLookupFragmentImpl implements UserRepositoryPlainLookupFragment {

  @PersistenceContext private EntityManager entityManager;

  /**
   * Resolves the user via {@link EntityManager#find(Class, Object)}, without an entity graph and
   * without auto-flushing.
   *
   * @param id the user id; must not be {@code null}
   * @return the user, or empty when none exists
   */
  @NotNull
  @Override
  public Optional<User> findPlainById(@NotNull UUID id) {
    return Optional.ofNullable(entityManager.find(User.class, id));
  }
}
