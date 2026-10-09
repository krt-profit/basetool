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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PromotionLevelContent} entries, providing category-scoped
 * lookups used when rendering the rank-level expectation table. Level contents inherit their
 * squadron scope from {@code category.topic.owningSquadron}; the {@code *Scoped} finders below
 * apply that filter ({@code null} scope = admin "all squadrons" mode).
 */
@Repository
public interface PromotionLevelContentRepository
    extends JpaRepository<PromotionLevelContent, UUID> {

  /**
   * Returns every {@link PromotionLevelContent} attached to the given category ordered by {@link
   * PromotionLevel}, so the rank-progression view can be rendered without re-sorting.
   *
   * @param categoryId identifier of the parent {@link
   *     de.greluc.krt.profit.basetool.backend.promotion.internal.PromotionCategory}
   * @return the category's level contents ordered by ascending {@link PromotionLevel}
   */
  List<PromotionLevelContent> findAllByCategoryIdOrderByLevel(UUID categoryId);

  /**
   * Squadron-scoped paged read across all categories. When {@code owningSquadronId} is {@code null}
   * the result spans every squadron (admin "all squadrons" mode); a non-null id restricts the
   * result to level contents whose category's topic is owned by that squadron.
   *
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of level contents visible to the caller
   */
  @Query(
      """
      SELECT lc FROM PromotionLevelContent lc WHERE :owningSquadronId IS NULL OR
      lc.category.topic.owningSquadron.id = :owningSquadronId
      """)
  Page<PromotionLevelContent> findAllScoped(
      @Param("owningSquadronId") UUID owningSquadronId, Pageable pageable);

  /**
   * Squadron-scoped unpaginated read of one category's level contents ordered by {@link
   * PromotionLevel}. A category outside the caller's scope yields an empty list, so a forged {@code
   * categoryId} cannot leak a foreign squadron's level contents.
   *
   * @param categoryId identifier of the parent category
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @return the category's level contents in level order, scoped to the caller
   */
  @Query(
      """
      SELECT lc FROM PromotionLevelContent lc WHERE lc.category.id = :categoryId AND
      (:owningSquadronId IS NULL OR lc.category.topic.owningSquadron.id =
      :owningSquadronId) ORDER BY lc.level ASC
      """)
  List<PromotionLevelContent> findAllByCategoryIdScopedOrdered(
      @Param("categoryId") UUID categoryId, @Param("owningSquadronId") UUID owningSquadronId);

  /**
   * Looks up the single {@link PromotionLevelContent} entry that describes the expectations of the
   * given category at the given level.
   *
   * @param categoryId identifier of the parent category
   * @param level the {@link PromotionLevel} to resolve
   * @return the matching entry, or {@link Optional#empty()} if none exists
   */
  Optional<PromotionLevelContent> findByCategoryIdAndLevel(UUID categoryId, PromotionLevel level);
}
