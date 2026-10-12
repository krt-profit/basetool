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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.UexCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link UexCategory}. The PK is the UEX integer id (1..98+),
 * deterministic across runs.
 */
@Repository
public interface UexCategoryRepository extends JpaRepository<UexCategory, Integer> {

  /**
   * Lists categories whose {@code is_game_related} flag is set — the inner-loop input for the R2
   * {@code UexItemSyncService}. Sorted by id so the {@code /items?id_category=<n>} walk is
   * deterministic across runs.
   *
   * @return game-related categories sorted by integer id
   */
  List<UexCategory> findAllByIsGameRelatedTrueOrderByIdAsc();
}
