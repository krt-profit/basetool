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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/** Shared JPQL readership for web order pages, counts, lookups and material representations. */
public final class JobOrderScope {

  /** Non-instantiable constant holder. */
  private JobOrderScope() {}

  /** Predicate for physical stock-demand, allocation and linked-need representations. */
  public static final String STOCK_KINDS = "o.type IN ('MATERIAL', 'ITEM')";

  private static final String ACTIVE_CALLER =
      """
      EXISTS (SELECT u.id FROM User u WHERE u.id = :callerId
        AND u.approvalStatus = de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus.ACTIVE
        AND u.inKeycloak = true AND u.enabledInKeycloak = true)
      """;

  /** Current linked identity predicate for the holder alias {@code h} and the caller. */
  public static final String ACTIVE_HOLDER_LINK = "h.userId = :callerId AND " + ACTIVE_CALLER;

  /** Personal readership for a money order through an active link or historical handover. */
  public static final String MONEY_PARTICIPANT_LINK =
      """
      (o.type = 'AUEC' AND (
        EXISTS (SELECT h.id FROM JobOrderMoneyHolder h WHERE h.orderId = o.id AND h.userId = :callerId)
        OR EXISTS (SELECT r.id FROM JobOrderMoneyRecipient r WHERE r.orderId = o.id
          AND r.correctedIntoId IS NULL
          AND (r.active = true OR EXISTS (SELECT m.id FROM JobOrderMoneyOperation m
            WHERE m.orderId = o.id AND (m.recipientId = r.id OR EXISTS (
              SELECT prior.id FROM JobOrderMoneyRecipient prior
              WHERE prior.orderId = o.id AND prior.id = m.recipientId AND prior.correctedIntoId = r.id))
            AND NOT EXISTS (SELECT c.id FROM JobOrderMoneyCorrectionEntry c
              WHERE c.orderId = o.id AND c.originalOperationId = m.operationId AND c.amount = 0
                AND NOT EXISTS (SELECT later.id FROM JobOrderMoneyCorrectionEntry later
                  WHERE later.orderId = c.orderId AND later.originalOperationId = c.originalOperationId
                    AND later.id > c.id))))
          AND (r.userId = :callerId OR EXISTS (SELECT h.id FROM JobOrderMoneyHolder h
            WHERE h.orderId = o.id AND h.userId = :callerId AND
              (h.id = r.holderId OR EXISTS (SELECT alias.id FROM JobOrderMoneyHolder alias
                WHERE alias.id = r.holderId AND alias.mergedIntoId = h.id)))))
      ) AND
      """
          + ACTIVE_CALLER
          + ")";

  /** Complete web order readership, independent of the reduced allocation and Exchange scope. */
  public static final String READ_PREDICATE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND (o.responsibleOrgUnit.id = :activeOrgUnitId
          OR o.requestingOrgUnit.id = :activeOrgUnitId))
        OR (:activeOrgUnitId IS NULL AND (o.responsibleOrgUnit.id IN :memberOrgUnitIds
          OR o.requestingOrgUnit.id IN :memberOrgUnitIds))
        OR EXISTS (SELECT h.id FROM JobOrderUnitReadHistory h WHERE h.jobOrder = o
          AND ((:activeOrgUnitId IS NOT NULL AND h.orgUnit.id = :activeOrgUnitId)
            OR (:activeOrgUnitId IS NULL AND h.orgUnit.id IN :memberOrgUnitIds)))
        OR EXISTS (SELECT p.id FROM JobOrderParticipation p WHERE p.jobOrder = o
          AND ((:activeOrgUnitId IS NOT NULL AND p.claimingOrgUnit.id = :activeOrgUnitId)
            OR (:activeOrgUnitId IS NULL AND p.claimingOrgUnit.id IN :memberOrgUnitIds)))
        OR EXISTS (SELECT a.id FROM JobOrderAssignee a WHERE a.jobOrder = o
          AND a.user.id = :callerId)
        OR
      """
          + MONEY_PARTICIPANT_LINK
          + "\n)\n";
}
