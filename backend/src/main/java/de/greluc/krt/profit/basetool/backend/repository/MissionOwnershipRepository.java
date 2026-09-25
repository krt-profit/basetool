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

import de.greluc.krt.profit.basetool.backend.model.MissionOwnership;
import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for Mission Ownership. */
public interface MissionOwnershipRepository extends JpaRepository<MissionOwnership, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code MissionId}. */
  Optional<MissionOwnership> findByMissionId(UUID missionId);

  /**
   * Reassigns every {@code mission_ownership} row owned by {@code oldUser} to {@code newUser},
   * alongside {@link
   * MissionRepository#updateOwner(de.greluc.krt.profit.basetool.backend.model.User,
   * de.greluc.krt.profit.basetool.backend.model.User)}, so the user can be deleted.
   *
   * <p>Does not bump {@code version}.
   *
   * @param oldUser the user being removed, whose owned companion rows are reassigned
   * @param newUser the replacement owner (the fallback admin)
   */
  @Modifying
  @Query("UPDATE MissionOwnership mo SET mo.owner = :newUser WHERE mo.owner = :oldUser")
  void updateOwner(@NotNull User oldUser, @NotNull User newUser);
}
