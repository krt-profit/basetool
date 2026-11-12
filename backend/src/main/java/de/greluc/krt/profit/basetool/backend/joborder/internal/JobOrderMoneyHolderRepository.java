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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Resolves holder identity within an order, independent of the member's other orders. */
interface JobOrderMoneyHolderRepository extends JpaRepository<JobOrderMoneyHolder, UUID> {

  /** The two-key PostgreSQL advisory namespace for order money identities. */
  int IDENTITY_LOCK_SPACE = 0x4A4F4D49;

  Optional<JobOrderMoneyHolder> findByOrderIdAndUserId(UUID orderId, UUID userId);

  Optional<JobOrderMoneyHolder> findByOrderIdAndId(UUID orderId, UUID id);

  List<JobOrderMoneyHolder> findAllByOrderIdOrderById(UUID orderId);

  List<JobOrderMoneyHolder> findAllByMergedIntoIdIn(Collection<UUID> sourceIds);

  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderAliases$Pair(original, survivor)
      FROM JobOrderMoneyHolder original
      JOIN JobOrderMoneyHolder survivor ON survivor.orderId = original.orderId
      WHERE original.userId = :source AND survivor.userId = :target
        AND original.orderId IN :orderIds
      """)
  List<JobOrderMoneyHolderAliases.Pair> collidingHolders(
      UUID source, UUID target, List<UUID> orderIds);

  @Query("SELECT h.userId FROM JobOrderMoneyHolder h WHERE h.orderId = :orderId AND h.id = :id")
  Optional<UUID> accountFor(UUID orderId, UUID id);

  @Query(
      value = "SELECT 1 FROM pg_advisory_xact_lock(" + IDENTITY_LOCK_SPACE + ", :key)",
      nativeQuery = true)
  int lockAccountIdentity(int key);

  @Query(
      value =
          """
          WITH spellings AS (
            SELECT lower(name) AS name FROM unnest(CAST(:spellings AS text[])) AS names(name)
          )
          SELECT o.id
          FROM job_order o
          WHERE lower(o.handle) IN (SELECT name FROM spellings) OR o.id IN (
            SELECT h.order_id FROM job_order_money_holder h
            WHERE h.user_id = :userId OR (h.user_id IS NULL AND lower(h.accountless_name) IN
                                        (SELECT name FROM spellings))
            UNION
            SELECT p.order_id FROM job_order_money_operation p
            WHERE p.actor_id = :userId OR p.selected_user_id = :userId
                  OR lower(p.selected_accountless_name) IN (SELECT name FROM spellings)
            UNION
            SELECT a.order_id FROM job_order_linked_money_attempt a
            WHERE :userId IN (a.actor_id, a.decision_actor_id, a.selected_user_id, a.counterparty_user_id)
               OR lower(a.selected_accountless_name) IN (SELECT name FROM spellings)
               OR lower(a.counterparty_name) IN (SELECT name FROM spellings)
            UNION
            SELECT r.order_id FROM job_order_money_recipient r
            WHERE r.user_id = :userId OR r.correction_actor_id = :userId
                  OR lower(r.accountless_name) IN (SELECT name FROM spellings)
            UNION
            SELECT t.order_id FROM job_order_money_target_change t WHERE t.actor_id = :userId
            UNION
            SELECT c.order_id FROM job_order_money_holder_change c
            WHERE :userId IN (c.actor_id, c.previous_user_id, c.user_id)
               OR lower(c.previous_name) IN (SELECT name FROM spellings)
               OR lower(c.name) IN (SELECT name FROM spellings)
            UNION
            SELECT h.job_order_id FROM job_order_handover h
            WHERE lower(h.recipient_handle) IN (SELECT name FROM spellings)
            UNION
            SELECT h.job_order_id FROM job_order_item_handover h
            WHERE lower(h.recipient_handle) IN (SELECT name FROM spellings)
          )
          ORDER BY o.id
          FOR NO KEY UPDATE OF o
          """,
      nativeQuery = true)
  List<UUID> lockPrivacyOrders(UUID userId, String[] spellings);

  @Modifying
  @Query(
      value =
          """
          WITH spellings AS (
            SELECT lower(name) AS name FROM unnest(CAST(:spellings AS text[])) AS names(name)
          ), redactions AS (
            SELECT a.id,
              a.selected_accountless_name IS NOT NULL AND (
                lower(a.selected_accountless_name) IN (SELECT name FROM spellings) OR EXISTS (
                  SELECT 1 FROM job_order_money_holder original
                  JOIN job_order_money_holder linked ON linked.id = COALESCE(original.merged_into_id, original.id)
                  WHERE linked.user_id = :userId
                    AND original.id = CASE
                      WHEN a.kind = 'PROVISION' THEN a.holder_id
                      WHEN a.kind IN ('COLLECTION', 'REQUESTER_RETURN') THEN a.destination_holder_id
                    END
                )) AS redact_selection,
              lower(a.counterparty_name) IN (SELECT name FROM spellings) AS redact_counterparty
            FROM job_order_linked_money_attempt a WHERE a.order_id IN (:orderIds)
          )
          UPDATE job_order_linked_money_attempt a
          SET selected_accountless_name = CASE WHEN r.redact_selection THEN NULL ELSE a.selected_accountless_name END,
              counterparty_name = CASE WHEN r.redact_counterparty THEN NULL ELSE a.counterparty_name END
          FROM redactions r
          WHERE a.id = r.id AND (r.redact_selection OR r.redact_counterparty)
          """,
      nativeQuery = true)
  int anonymiseLinkedAttemptNames(UUID userId, String[] spellings, List<UUID> orderIds);

  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_money_holder
          SET accountless_name = :sentinel, revision = revision + 1
          WHERE order_id IN (:orderIds) AND merged_into_id IS NULL
            AND accountless_name IS DISTINCT FROM :sentinel
            AND (user_id = :userId OR (user_id IS NULL AND lower(accountless_name) IN
                 (SELECT lower(name) FROM unnest(CAST(:spellings AS text[])) AS names(name))))
          """,
      nativeQuery = true)
  int anonymiseNames(UUID userId, String[] spellings, List<UUID> orderIds, String sentinel);

  @Modifying
  @Query(
      value =
          """
          UPDATE job_order_money_holder
          SET user_id = NULL, revision = revision + 1
          WHERE user_id = :userId AND order_id IN (:orderIds)
          """,
      nativeQuery = true)
  void unlinkAccount(UUID userId, List<UUID> orderIds);

  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE job_order_money_holder
          SET user_id = :target, revision = revision + 1
          WHERE user_id = :source AND order_id IN (:orderIds)
          """,
      nativeQuery = true)
  int moveLinkedHolders(UUID source, UUID target, List<UUID> orderIds);
}
