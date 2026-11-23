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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The member's job-order read set on the exchange (REQ-OXCH-001), decided from the database on
 * every call: the orders to which the member has a current own relation. A unit relation counts
 * through the active units the member directly belongs to while holding {@code KRT Member}; an
 * assignee entry counts by itself. The Profit-wide read, the leadership cascade, contextual
 * grants, the administrator's scope, a pin, the aUEC holder and recipient link, retained historical
 * reads and ended support never count, so the set never exceeds the web's read (REQ-ORDERS-052).
 *
 * <p>{@link #reads(UUID, UUID)} is the read check the exchange's writes on an existing order pass
 * to the shared commands (ADR-0405, ADR-0407).
 */
public interface JobOrderExchangeReadSet {

  /**
   * Resolves the member and the units through which their unit relations count.
   *
   * @param memberId the acting member
   * @return the member with the active units they directly belong to, none without {@code KRT
   *     Member}
   */
  @NotNull
  RelatingMember relatingMember(@NotNull UUID memberId);

  /**
   * Decides the member's relations to the given orders.
   *
   * @param member the resolved member
   * @param orderIds the orders to decide
   * @return each order of the read set with its relations, never empty; an order outside the read
   *     set or without a row is absent
   */
  @NotNull
  @Unmodifiable
  Map<UUID, Set<JobOrderRelation>> relations(
      @NotNull RelatingMember member, @NotNull Collection<UUID> orderIds);

  /**
   * Reads the member's own assignee notes on the given orders; another member's note is never
   * read.
   *
   * @param memberId the acting member
   * @param orderIds the orders
   * @return the member's note by order, only where they hold an entry with a note
   */
  @NotNull
  @Unmodifiable
  Map<UUID, String> ownNotes(@NotNull UUID memberId, @NotNull Collection<UUID> orderIds);

  /**
   * Decides whether one order is in the member's read set; a hidden, an unknown and a deleted order
   * answer alike.
   *
   * @param memberId the acting member
   * @param orderId the order
   * @return {@code true} iff the member has a current own relation to the order
   */
  default boolean reads(@NotNull UUID memberId, @NotNull UUID orderId) {
    return relations(relatingMember(memberId), List.of(orderId)).containsKey(orderId);
  }

  /**
   * A member with the units through which their unit relations count.
   *
   * @param memberId the member
   * @param unitIds the active units the member directly belongs to, empty without {@code KRT
   *     Member}
   */
  record RelatingMember(@NotNull UUID memberId, @NotNull @Unmodifiable Set<UUID> unitIds) {

    /** Holds the units as an unmodifiable copy. */
    public RelatingMember {
      unitIds = Set.copyOf(unitIds);
    }
  }
}
