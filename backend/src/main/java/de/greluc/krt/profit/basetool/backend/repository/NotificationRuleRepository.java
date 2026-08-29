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

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link NotificationRule}. The fetch-joined finders pull the selector
 * collection in one query so the engine (which evaluates rules off the request thread) and the
 * admin detail view never trip a lazy-initialization or N+1.
 */
@Repository
public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {

  /**
   * Returns the enabled rules for an event type with their selectors eagerly loaded.
   *
   * @param eventType the event type to match
   * @return the matching enabled rules (selectors fetched); never {@code null}
   */
  @Query(
      """
      SELECT DISTINCT r FROM NotificationRule r LEFT JOIN FETCH r.selectors
      WHERE r.eventType = :eventType AND r.enabled = true
      """)
  List<NotificationRule> findEnabledByEventTypeWithSelectors(
      @Param("eventType") NotificationEventType eventType);

  /**
   * Returns every rule with selectors eagerly loaded, for the admin list view.
   *
   * @return all rules (selectors fetched); never {@code null}
   */
  @Query("SELECT DISTINCT r FROM NotificationRule r LEFT JOIN FETCH r.selectors")
  List<NotificationRule> findAllWithSelectors();

  /**
   * Returns one rule by id with its selectors eagerly loaded, for the admin detail view.
   *
   * @param id the rule id
   * @return the rule (selectors fetched), or empty when unknown
   */
  @Query("SELECT r FROM NotificationRule r LEFT JOIN FETCH r.selectors WHERE r.id = :id")
  Optional<NotificationRule> findByIdWithSelectors(@Param("id") UUID id);

  /**
   * Removes every {@code SPECIFIC_USER} selector that targets the given user, as part of the hard
   * account deletion (REQ-DATA-008). Declared here rather than on a selector repository of its own
   * because {@code NotificationRuleSelector} has no repository — it is otherwise reached only
   * through its owning rule's cascade.
   *
   * <p>Without this the selector outlives the account and keeps <em>manufacturing</em> orphans: the
   * rule engine returns the dead subject verbatim with no existence check and a notification row is
   * persisted for it, so every subsequent matching event mints a new row for a user who no longer
   * exists. The admin rule editor shows only the raw UUID, so the broken selector is invisible
   * there.
   *
   * <p>A rule left with no selectors is kept, not deleted: it may still carry role- or org-relative
   * selectors, and an emptied rule is an admin-visible configuration question rather than something
   * a user deletion should silently decide.
   *
   * @param userId the departing user's Keycloak {@code sub} (equal to {@code app_user.id})
   * @return the number of selectors removed, for the audit summary event
   */
  @Modifying
  @Query("DELETE FROM NotificationRuleSelector s WHERE s.userId = :userId")
  int deleteSelectorsByUserId(@Param("userId") UUID userId);
}
