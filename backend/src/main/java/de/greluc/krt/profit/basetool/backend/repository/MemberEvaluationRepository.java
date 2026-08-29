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

import de.greluc.krt.profit.basetool.backend.model.MemberEvaluation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link MemberEvaluation}. In addition to the standard CRUD finders it
 * exposes a JOIN-FETCH variant the eligibility service uses to evaluate a member's grades against a
 * {@code RankRequirement} without firing lazy-load queries per row.
 *
 * <p>Evaluations inherit their squadron scope from {@code category.topic.owningSquadron}; the
 * {@code *Scoped} finders apply that filter so a member's personal list and the admin overview only
 * ever show the active squadron's grades ({@code null} scope = admin "all squadrons" mode).
 */
@Repository
public interface MemberEvaluationRepository extends JpaRepository<MemberEvaluation, UUID> {

  /**
   * Returns every evaluation belonging to the given JWT-sub, without eager joins. Used for the
   * user-facing "my evaluations" list where category / topic data is rendered separately.
   *
   * @param userId the {@code app_user.id} of the member
   * @return every evaluation row for that member, possibly empty
   */
  List<MemberEvaluation> findAllByUserId(UUID userId);

  /**
   * Paginated variant of {@link #findAllByUserId(String)} for the personal view.
   *
   * @param userId the {@code app_user.id} of the member
   * @param pageable Spring Data pagination/sort instructions
   * @return a page of evaluations for the member
   */
  Page<MemberEvaluation> findAllByUserId(UUID userId, Pageable pageable);

  /**
   * Squadron-scoped variant of {@link #findAllByUserId(String)} for the "my evaluations" list, so a
   * member who belongs to more than one squadron only sees the active squadron's grades. {@code
   * null} scope spans every squadron.
   *
   * @param userId the {@code app_user.id} of the member
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @return the member's evaluations visible in the scope, possibly empty
   */
  @Query(
      """
      SELECT e FROM MemberEvaluation e WHERE e.userId = :userId AND (:owningSquadronId IS NULL OR
      e.category.topic.owningSquadron.id = :owningSquadronId)
      """)
  List<MemberEvaluation> findAllByUserIdScoped(
      @Param("userId") UUID userId, @Param("owningSquadronId") UUID owningSquadronId);

  /**
   * Paginated squadron-scoped variant of {@link #findAllByUserIdScoped(String, UUID)}.
   *
   * @param userId the {@code app_user.id} of the member
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @param pageable Spring Data pagination/sort instructions
   * @return a page of the member's evaluations visible in the scope
   */
  @Query(
      """
      SELECT e FROM MemberEvaluation e WHERE e.userId = :userId AND (:owningSquadronId IS NULL OR
      e.category.topic.owningSquadron.id = :owningSquadronId)
      """)
  Page<MemberEvaluation> findAllByUserIdScoped(
      @Param("userId") UUID userId,
      @Param("owningSquadronId") UUID owningSquadronId,
      Pageable pageable);

  /**
   * Squadron-scoped admin/officer overview of every member's evaluations. {@code null} scope spans
   * every squadron (admin "all squadrons" mode); a non-null id restricts the result to evaluations
   * whose category's topic is owned by that squadron.
   *
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @param pageable Spring Data pagination/sort instructions
   * @return a page of evaluations visible to the caller
   */
  @Query(
      """
      SELECT e FROM MemberEvaluation e WHERE :owningSquadronId IS NULL OR
      e.category.topic.owningSquadron.id = :owningSquadronId
      """)
  Page<MemberEvaluation> findAllScoped(
      @Param("owningSquadronId") UUID owningSquadronId, Pageable pageable);

  /**
   * Looks up a single evaluation by its primary key components.
   *
   * @param userId the {@code app_user.id} of the member
   * @param categoryId the category the evaluation refers to
   * @return the evaluation if one exists for the pair, otherwise empty
   */
  Optional<MemberEvaluation> findByUserIdAndCategoryId(UUID userId, UUID categoryId);

  /**
   * Existence probe used by the upsert flow to decide between insert and update without loading the
   * row.
   *
   * @param userId the {@code app_user.id} of the member
   * @param categoryId the category the potential evaluation would refer to
   * @return {@code true} iff a row exists for the pair
   */
  boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);

  /**
   * Returns every evaluation for a user with the parent {@code PromotionCategory} and its {@code
   * PromotionTopic} eagerly fetched. The eligibility evaluator uses this method to avoid N+1 lazy
   * loads when grouping evaluations by topic.
   *
   * @param userId the {@code app_user.id} of the member
   * @return evaluations with category+topic already populated, possibly empty
   */
  @Query(
      """
      SELECT e FROM MemberEvaluation e
      JOIN FETCH e.category c
      JOIN FETCH c.topic
      WHERE e.userId = :userId
      """)
  List<MemberEvaluation> findAllByUserIdWithCategoryAndTopic(UUID userId);

  /**
   * Squadron-scoped variant of {@link #findAllByUserIdWithCategoryAndTopic(String)} used by the
   * eligibility evaluator. Restricting the member's grades to the active squadron keeps a global
   * ("any N categories") rank requirement from counting grades the member earned in a different
   * squadron's catalog. {@code null} scope spans every squadron (admin "all squadrons" mode).
   *
   * @param userId the {@code app_user.id} of the member
   * @param owningSquadronId the active squadron scope, or {@code null} for all squadrons
   * @return the member's evaluations (category+topic fetched) within the scope, possibly empty
   */
  @Query(
      """
      SELECT e FROM MemberEvaluation e
      JOIN FETCH e.category c
      JOIN FETCH c.topic t
      WHERE e.userId = :userId
      AND (:owningSquadronId IS NULL OR t.owningSquadron.id = :owningSquadronId)
      """)
  List<MemberEvaluation> findAllByUserIdWithCategoryAndTopicScoped(
      @Param("userId") UUID userId, @Param("owningSquadronId") UUID owningSquadronId);

  /**
   * Deletes every promotion grade of the given member as part of the hard account deletion
   * (REQ-DATA-008). {@code member_evaluation.user_id} is a plain {@code VARCHAR} holding the JWT
   * sub with no foreign key to {@code app_user}, so nothing cascades and the grades — an assessment
   * of a named person — would otherwise outlive the account indefinitely.
   *
   * <p>Set-based and without {@code clearAutomatically}, because it runs inside the user-deletion
   * transaction where evicting the persistence context would detach the {@code User} being deleted.
   *
   * @param userId the departing member's {@code app_user.id}
   * @return the number of grades removed, for the audit summary event
   */
  @Modifying
  @Query("DELETE FROM MemberEvaluation e WHERE e.userId = :userId")
  int deleteAllByUserId(@Param("userId") UUID userId);
}
