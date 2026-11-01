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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The segment of the order overview, in display order: „Meine", „Ich bearbeite", „Zu bearbeiten"
 * and „Alle" (REQ-ORDERS-040, REQ-UI-027).
 */
public enum OrderListScope {

  /** The orders the caller's own org units requested. */
  MINE("orders.index.scope.mine"),

  /** The orders the caller holds an assignee entry on. */
  ASSIGNED("orders.index.scope.assigned"),

  /** The orders one of the caller's own units processes. */
  TO_PROCESS("orders.index.scope.toProcess"),

  /** The caller's whole visible order queue. */
  ALL("filter.all");

  private final String labelKey;

  OrderListScope(@NotNull String labelKey) {
    this.labelKey = labelKey;
  }

  /**
   * The message key of the segment label.
   *
   * @return the i18n key
   */
  @NotNull
  public String labelKey() {
    return labelKey;
  }

  /**
   * Parses the {@code scope} request parameter.
   *
   * @param value the raw parameter, or {@code null}
   * @return the scope, or {@code null} when the value is absent or unknown
   */
  @Nullable
  public static OrderListScope parseOrNull(@Nullable String value) {
    return Arrays.stream(values()).filter(s -> s.name().equals(value)).findFirst().orElse(null);
  }

  /**
   * The segments a caller may choose from, in display order.
   *
   * <p>Whoever sees the queue gets „Ich bearbeite", „Zu bearbeiten" and „Alle"; an assignee without
   * queue access gets „Ich bearbeite" alone; „Meine" joins whenever the caller also has a second
   * list to switch to. A requester-only caller has no choice.
   *
   * @param canViewQueue whether the caller may enter the Job-Order area
   * @param canViewOwn whether the caller may view the orders their own units requested
   * @param canViewAssigned whether the caller holds an assignee entry
   * @return the offered segments; empty when there is nothing to choose
   */
  @NotNull
  public static List<OrderListScope> offered(
      boolean canViewQueue, boolean canViewOwn, boolean canViewAssigned) {
    return Arrays.stream(values())
        .filter(scope -> scope.isOffered(canViewQueue, canViewOwn, canViewAssigned))
        .toList();
  }

  /**
   * Resolves the list a caller gets: the requested segment when it is offered, otherwise the
   * caller's default list. A {@code scope} parameter therefore never moves a caller onto a list
   * they cannot choose.
   *
   * @param requested the validated {@code scope} parameter, or {@code null}
   * @param canViewQueue whether the caller may enter the Job-Order area
   * @param canViewOwn whether the caller may view the orders their own units requested
   * @param canViewAssigned whether the caller holds an assignee entry
   * @return the scope to read; the caller has at least one capability
   */
  @NotNull
  public static OrderListScope resolve(
      @Nullable OrderListScope requested,
      boolean canViewQueue,
      boolean canViewOwn,
      boolean canViewAssigned) {
    if (requested != null && requested.isOffered(canViewQueue, canViewOwn, canViewAssigned)) {
      return requested;
    }
    if (canViewQueue) {
      return ALL;
    }
    return canViewOwn ? MINE : ASSIGNED;
  }

  private boolean isOffered(boolean canViewQueue, boolean canViewOwn, boolean canViewAssigned) {
    return switch (this) {
      case MINE -> canViewOwn && (canViewQueue || canViewAssigned);
      case ASSIGNED -> canViewQueue || canViewAssigned;
      case TO_PROCESS, ALL -> canViewQueue;
    };
  }
}
