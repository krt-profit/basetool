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

import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link Notification}. Every non-admin lookup MUST filter by {@code
 * recipientSub} so a caller can never see another user's inbox (REQ-NOTIF-004); the bulk mutations
 * are single atomic statements to avoid the optimistic-locking traps documented in {@code
 * CLAUDE.md}.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /**
   * Returns a recipient's notifications as a page (sorted by the {@link Pageable}).
   *
   * @param recipientSub Keycloak {@code sub} of the caller
   * @param pageable page request
   * @return the matching page
   */
  Page<Notification> findAllByRecipientSub(UUID recipientSub, Pageable pageable);

  /**
   * Returns a recipient's most recent notifications first, capped by the {@link Pageable} size.
   * Used by the bell dropdown which shows only the latest handful.
   *
   * @param recipientSub Keycloak {@code sub} of the caller
   * @param pageable page request supplying the cap (size) and offset
   * @return the most-recent-first list
   */
  List<Notification> findByRecipientSubOrderByCreatedAtDesc(UUID recipientSub, Pageable pageable);

  /**
   * Returns one notification only if it belongs to the caller. The empty result is deliberately
   * indistinguishable from "unknown id" so a caller cannot probe foreign ids.
   *
   * @param id notification primary key
   * @param recipientSub Keycloak {@code sub} of the caller
   * @return the notification, or empty when missing or owned by someone else
   */
  Optional<Notification> findByIdAndRecipientSub(UUID id, UUID recipientSub);

  /**
   * Counts a recipient's unread notifications; backs the always-on unread badge.
   *
   * @param recipientSub Keycloak {@code sub} of the caller
   * @return the number of unread notifications
   */
  long countByRecipientSubAndReadFalse(UUID recipientSub);

  /**
   * Marks every unread notification of a recipient read in one atomic statement, stamping {@code
   * readAt}. Clears the persistence context so callers re-read fresh state.
   *
   * @param recipientSub Keycloak {@code sub} of the caller
   * @param readAt the read timestamp to stamp
   * @return the number of rows updated
   */
  @Modifying(clearAutomatically = true)
  @Query(
      """
      update Notification n set n.read = true, n.readAt = :readAt
      where n.recipientSub = :recipientSub and n.read = false
      """)
  int markAllReadForRecipient(
      @Param("recipientSub") UUID recipientSub, @Param("readAt") Instant readAt);

  /**
   * Deletes every <em>read</em> notification of a recipient in one atomic statement (the user's
   * "clear read" action). Unread notifications are untouched.
   *
   * @param recipientSub Keycloak {@code sub} of the caller
   * @return the number of rows deleted
   */
  @Modifying(clearAutomatically = true)
  @Query("delete from Notification n where n.recipientSub = :recipientSub and n.read = true")
  int deleteAllReadForRecipient(@Param("recipientSub") UUID recipientSub);

  /**
   * Deletes read notifications whose read timestamp is older than the cutoff; backs the scheduled
   * retention sweep (REQ-NOTIF-009).
   *
   * @param cutoff delete read notifications read before this instant
   * @return the number of rows deleted
   */
  @Modifying
  @Query("delete from Notification n where n.read = true and n.readAt < :cutoff")
  int deleteReadOlderThan(@Param("cutoff") Instant cutoff);

  /**
   * Deletes the complete notification history of one recipient, read and unread alike, as part of
   * the hard account deletion (REQ-DATA-008). {@code recipient_sub} is a loose reference with no
   * foreign key to {@code app_user} (V155 says so explicitly and defers row lifetime to retention),
   * so nothing cascades — and the retention sweep {@link #deleteReadOlderThan(Instant)} only ever
   * reaps <em>read</em> rows, which is why a departed member's unread backlog would otherwise
   * survive forever.
   *
   * <p>Deliberately without {@code clearAutomatically}: this runs inside the user-deletion
   * transaction, where evicting the persistence context would detach the {@code User} row that is
   * about to be deleted.
   *
   * @param recipientSub Keycloak {@code sub} of the departing recipient (equal to {@code
   *     app_user.id})
   * @return the number of rows deleted, for the audit summary event
   */
  @Modifying
  @Query("delete from Notification n where n.recipientSub = :recipientSub")
  int deleteAllForRecipient(@Param("recipientSub") UUID recipientSub);

  /**
   * The recipient {@code sub}s holding an outstanding notification of one of the given types for a
   * loose entity reference — collected <em>before</em> {@link #deleteByTypeInAndEntity} so the
   * caller can push a live inbox/badge refresh to exactly those recipients once their now-stale
   * items are removed (REQ-NOTIF-018).
   *
   * @param types the notification types to match (any-of)
   * @param entityType the loose entity-type tag
   * @param entityId the loose entity id
   * @return the distinct recipient subs; empty when none match
   */
  @Query(
      """
      select distinct n.recipientSub from Notification n
      where n.type in :types and n.entityType = :entityType and n.entityId = :entityId
      """)
  List<UUID> findRecipientSubsByTypeInAndEntity(
      @Param("types") Set<NotificationType> types,
      @Param("entityType") String entityType,
      @Param("entityId") UUID entityId);

  /**
   * Deletes every notification of one of the given types tagged with the loose entity reference in
   * one atomic statement — clears the now-stale "action needed" items a lifecycle-terminating event
   * supersedes (REQ-NOTIF-018). Clears the persistence context so callers re-read fresh state.
   *
   * @param types the notification types to delete (any-of)
   * @param entityType the loose entity-type tag
   * @param entityId the loose entity id
   * @return the number of rows deleted
   */
  @Modifying(clearAutomatically = true)
  @Query(
      """
      delete from Notification n
      where n.type in :types and n.entityType = :entityType and n.entityId = :entityId
      """)
  int deleteByTypeInAndEntity(
      @Param("types") Set<NotificationType> types,
      @Param("entityType") String entityType,
      @Param("entityId") UUID entityId);
}
