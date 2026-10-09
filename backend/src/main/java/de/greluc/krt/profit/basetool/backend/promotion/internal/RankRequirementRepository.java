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
 * Spring Data repository for {@link RankRequirement}. The custom finders below are tuned for the
 * eligibility evaluator, which needs every requirement that applies to a specific {@code (fromRank,
 * toRank)} transition and the distinct set of configured transitions.
 */
@Repository
public interface RankRequirementRepository extends JpaRepository<RankRequirement, UUID> {

  /**
   * Returns every requirement configured for a specific rank transition, ordered by id so the
   * eligibility result is deterministic across calls. Used by the eligibility service when
   * evaluating one transition.
   *
   * @param fromRank the rank the member currently holds
   * @param toRank the rank the member would be promoted to
   * @return all matching requirements, possibly empty
   */
  List<RankRequirement> findAllByFromRankAndToRankOrderByIdAsc(int fromRank, int toRank);

  /**
   * Squadron-scoped paged read for the admin list endpoint. When {@code owningSquadronId} is {@code
   * null} the result spans every squadron (admin "all squadrons" mode); a non-null id restricts the
   * result to a single squadron's requirements via the requirement's own {@code owningSquadron}.
   *
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @param pageable Spring Data paging and sorting parameters
   * @return a page of rank requirements visible to the caller
   */
  @Query(
      """
      SELECT r FROM RankRequirement r WHERE :owningSquadronId IS NULL OR r.owningSquadron.id =
      :owningSquadronId
      """)
  Page<RankRequirement> findAllScoped(
      @Param("owningSquadronId") UUID owningSquadronId, Pageable pageable);

  /**
   * Squadron-scoped variant of {@link #findAllByFromRankAndToRankOrderByIdAsc(int, int)}. Same
   * {@code null}-means-all-squadrons contract as {@link #findAllScoped(UUID, Pageable)}.
   *
   * @param fromRank the rank the member currently holds
   * @param toRank the rank the member would be promoted to
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @return matching requirements visible to the caller, ordered by id
   */
  @Query(
      """
      SELECT r FROM RankRequirement r WHERE r.fromRank = :fromRank AND r.toRank = :toRank AND
      (:owningSquadronId IS NULL OR r.owningSquadron.id = :owningSquadronId) ORDER BY r.id
      ASC
      """)
  List<RankRequirement> findScopedByFromRankAndToRank(
      @Param("fromRank") int fromRank,
      @Param("toRank") int toRank,
      @Param("owningSquadronId") UUID owningSquadronId);

  /**
   * Paginated variant of {@link #findAllByFromRankAndToRankOrderByIdAsc(int, int)} for admin REST
   * endpoints.
   *
   * @param fromRank the rank the member currently holds
   * @param toRank the rank the member would be promoted to
   * @param pageable Spring Data pagination/sort instructions
   * @return a page of matching requirements
   */
  Page<RankRequirement> findAllByFromRankAndToRank(int fromRank, int toRank, Pageable pageable);

  /**
   * Returns the distinct list of {@code (fromRank, toRank)} pairs that have at least one
   * requirement configured, ordered by {@code fromRank} descending so the most senior transitions
   * appear first. Used by the eligibility service to evaluate every defined transition for one
   * member.
   *
   * @return one row per configured transition, each carrying {@code [fromRank, toRank]}
   */
  @Query(
      """
      SELECT DISTINCT r.fromRank, r.toRank FROM RankRequirement r ORDER BY r.fromRank DESC,
      r.toRank DESC
      """)
  List<Object[]> findDistinctRankTransitions();

  /**
   * Squadron-scoped variant of {@link #findDistinctRankTransitions()} used by the eligibility
   * service so a member is evaluated only against the transitions configured for their squadron.
   * When {@code owningSquadronId} is {@code null} the result spans every squadron (admin "all
   * squadrons" mode).
   *
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @return one row per configured transition in the scope, carrying {@code [fromRank, toRank]}
   */
  @Query(
      """
      SELECT DISTINCT r.fromRank, r.toRank FROM RankRequirement r WHERE :owningSquadronId IS NULL
      OR r.owningSquadron.id = :owningSquadronId ORDER BY r.fromRank DESC, r.toRank DESC
      """)
  List<Object[]> findDistinctRankTransitionsScoped(
      @Param("owningSquadronId") UUID owningSquadronId);

  /**
   * JOIN-FETCH variant of {@link #findAllByFromRankAndToRankOrderByIdAsc(int, int)} used by the
   * eligibility evaluator so {@code topic}, {@code category} and {@code category.topic} are
   * hydrated up front and the per-requirement loop does not trigger lazy loads.
   *
   * <p>Two left joins on {@code topic} and {@code category} are required because each requirement
   * has at most one of them populated.
   *
   * @param fromRank the rank the member currently holds
   * @param toRank the rank the member would be promoted to
   * @return matching requirements with topic+category eagerly fetched
   */
  @Query(
      """
      SELECT r FROM RankRequirement r
      LEFT JOIN FETCH r.topic
      LEFT JOIN FETCH r.category c
      LEFT JOIN FETCH c.topic
      WHERE r.fromRank = :fromRank AND r.toRank = :toRank
      ORDER BY r.id ASC
      """)
  List<RankRequirement> findAllForRankTransitionWithRelations(int fromRank, int toRank);

  /**
   * Squadron-scoped variant of {@link #findAllForRankTransitionWithRelations(int, int)} used by the
   * eligibility evaluator so a member is matched only against their squadron's requirements. Same
   * eager-fetch shape and the same {@code null}-means-all-squadrons contract as the other scoped
   * finders.
   *
   * @param fromRank the rank the member currently holds
   * @param toRank the rank the member would be promoted to
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @return matching requirements with topic+category eagerly fetched, scoped to the caller
   */
  @Query(
      """
      SELECT r FROM RankRequirement r
      LEFT JOIN FETCH r.topic
      LEFT JOIN FETCH r.category c
      LEFT JOIN FETCH c.topic
      WHERE r.fromRank = :fromRank AND r.toRank = :toRank
      AND (:owningSquadronId IS NULL OR r.owningSquadron.id = :owningSquadronId)
      ORDER BY r.id ASC
      """)
  List<RankRequirement> findAllForRankTransitionWithRelationsScoped(
      @Param("fromRank") int fromRank,
      @Param("toRank") int toRank,
      @Param("owningSquadronId") UUID owningSquadronId);
}
