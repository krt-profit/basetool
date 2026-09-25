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
import java.util.Optional;
import java.util.UUID;

/**
 * Custom-repository fragment giving {@link MissionRepository} a collection-free, {@code
 * EntityManager.find}-based mission lookup for the authorization gates.
 */
public interface MissionRepositoryAuthorizationFragment {

  /**
   * Loads a mission by id for an authorization decision via {@code EntityManager.find}: no
   * collection graph, first-level-cache aware and never auto-flushing.
   *
   * <p>The caller lazy-loads the associations it needs within its own transaction.
   *
   * @param id the mission id
   * @return the mission, or empty when none exists
   */
  Optional<Mission> findByIdForAuthorization(UUID id);
}
