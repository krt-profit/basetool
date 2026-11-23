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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderScope;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads the member's part of the exchange job-order read set (REQ-OXCH-001, REQ-OXCH-002). */
public interface JobOrderReadSetRepository extends Repository<JobOrder, UUID> {

  /**
   * Decides each relation of a member to the given orders, one row per order of the read set.
   *
   * @param relatingUnitIds the active units the member directly belongs to while holding {@code KRT
   *     Member}
   * @param memberId the member
   * @param orderIds the orders
   * @return a row per order with at least one relation
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.joborder.internal"
          + ".JobOrderReadSetRepository$RelationRow(o.id,"
          + " CASE WHEN "
          + JobOrderScope.EXCHANGE_REQUESTING
          + " THEN true ELSE false END,"
          + " CASE WHEN "
          + JobOrderScope.EXCHANGE_RESPONSIBLE
          + " THEN true ELSE false END,"
          + " CASE WHEN "
          + JobOrderScope.EXCHANGE_SUPPORTING
          + " THEN true ELSE false END,"
          + " CASE WHEN "
          + JobOrderScope.EXCHANGE_ASSIGNEE
          + " THEN true ELSE false END)"
          + " FROM JobOrder o WHERE o.id IN :orderIds AND "
          + JobOrderScope.EXCHANGE_READ_SET)
  List<RelationRow> findRelations(
      @Param("relatingUnitIds") Collection<UUID> relatingUnitIds,
      @Param("memberId") UUID memberId,
      @Param("orderIds") Collection<UUID> orderIds);

  /**
   * Reads a member's own assignee notes on the given orders.
   *
   * @param memberId the member
   * @param orderIds the orders
   * @return a row per order where the member holds an entry with a note
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.joborder.internal"
          + ".JobOrderReadSetRepository$OwnNote(a.jobOrder.id, a.note)"
          + " FROM JobOrderAssignee a WHERE a.user.id = :memberId"
          + " AND a.jobOrder.id IN :orderIds AND a.note IS NOT NULL")
  List<OwnNote> findOwnNotes(
      @Param("memberId") UUID memberId, @Param("orderIds") Collection<UUID> orderIds);

  /**
   * One order of the read set with each of the member's relations to it.
   *
   * @param orderId the order
   * @param requesting whether a relating unit requests it
   * @param responsible whether a relating unit is responsible for it
   * @param supporting whether a relating unit actively supports it
   * @param assignee whether the member holds an assignee entry on it
   */
  record RelationRow(
      @NotNull UUID orderId,
      boolean requesting,
      boolean responsible,
      boolean supporting,
      boolean assignee) {}

  /**
   * The member's own note on one order.
   *
   * @param orderId the order
   * @param note the note
   */
  record OwnNote(@NotNull UUID orderId, @NotNull String note) {}
}
