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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * {@code EntityManager.find}-based implementation of {@link
 * MissionRepositoryAuthorizationFragment}, wired into {@link MissionRepository} by Spring Data's
 * {@code Impl} naming convention.
 */
public class MissionRepositoryAuthorizationFragmentImpl
    implements MissionRepositoryAuthorizationFragment {

  @PersistenceContext private EntityManager entityManager;

  /**
   * Resolves the mission through {@link EntityManager#find(Class, Object)}, applying no entity
   * graph and never auto-flushing.
   *
   * @param id the mission id
   * @return the mission, or empty when none exists
   */
  @NotNull
  @Override
  public Optional<Mission> findByIdForAuthorization(UUID id) {
    return Optional.ofNullable(entityManager.find(Mission.class, id));
  }
}
