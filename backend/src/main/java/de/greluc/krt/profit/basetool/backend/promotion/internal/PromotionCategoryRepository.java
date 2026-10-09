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
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link PromotionCategory} aggregates, providing topic-scoped
 * lookups used by the promotion UI and the eligibility engine. Categories inherit their squadron
 * scope from {@code topic.owningSquadron}; the {@code *Scoped} finders below apply that filter so a
 * caller never sees another squadron's catalog ({@code null} scope = admin "all squadrons" mode).
 */
@Repository
public interface PromotionCategoryRepository extends JpaRepository<PromotionCategory, UUID> {

  /**
   * Returns every {@link PromotionCategory} attached to the given topic, ordered by {@code
   * sortOrder} ascending so the evaluation table can be rendered without re-sorting.
   *
   * @param topicId identifier of the parent {@link
   *     de.greluc.krt.profit.basetool.backend.promotion.internal.PromotionTopic}
   * @return the topic's categories in display order
   */
  List<PromotionCategory> findAllByTopicIdOrderBySortOrderAsc(UUID topicId);

  /**
   * Returns a paginated slice of the {@link PromotionCategory} entries that belong to the given
   * topic.
   *
   * @param topicId identifier of the parent topic
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of categories scoped to the topic
   */
  Page<PromotionCategory> findAllByTopicId(UUID topicId, Pageable pageable);

  /**
   * Squadron-scoped paged read across all topics. When {@code owningSquadronId} is {@code null} the
   * result spans every squadron (admin "all squadrons" mode); a non-null id restricts the result to
   * categories whose topic is owned by that squadron.
   *
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of categories visible to the caller
   */
  @Query(
      """
      SELECT c FROM PromotionCategory c WHERE :owningSquadronId IS NULL OR
      c.topic.owningSquadron.id = :owningSquadronId
      """)
  Page<PromotionCategory> findAllScoped(
      @Param("owningSquadronId") UUID owningSquadronId, Pageable pageable);

  /**
   * Squadron-scoped paged read of the categories under one topic. A topic outside the caller's
   * scope yields an empty page, so a forged {@code topicId} cannot leak a foreign squadron's
   * categories.
   *
   * @param topicId identifier of the parent topic
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of categories under the topic, scoped to the caller
   */
  @Query(
      """
      SELECT c FROM PromotionCategory c WHERE c.topic.id = :topicId AND (:owningSquadronId IS NULL
      OR c.topic.owningSquadron.id = :owningSquadronId)
      """)
  Page<PromotionCategory> findAllByTopicIdScoped(
      @Param("topicId") UUID topicId,
      @Param("owningSquadronId") UUID owningSquadronId,
      Pageable pageable);

  /**
   * Squadron-scoped unpaginated read of one topic's categories in display order. Same
   * empty-on-foreign-topic contract as {@link #findAllByTopicIdScoped(UUID, UUID, Pageable)}.
   *
   * @param topicId identifier of the parent topic
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @return the topic's categories in display order, scoped to the caller
   */
  @Query(
      """
      SELECT c FROM PromotionCategory c WHERE c.topic.id = :topicId AND (:owningSquadronId IS NULL
      OR c.topic.owningSquadron.id = :owningSquadronId) ORDER BY c.sortOrder ASC
      """)
  List<PromotionCategory> findAllByTopicIdScopedOrdered(
      @Param("topicId") UUID topicId, @Param("owningSquadronId") UUID owningSquadronId);
}
