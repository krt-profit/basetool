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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import org.jetbrains.annotations.NotNull;

/**
 * How a book-in path decides whose Lager it may book into, beyond the shared owner check of {@link
 * BookInPolicy} (REQ-INV-032, REQ-SEC-005, APPSEC-01). The refinery store differs from the other
 * paths by design until the owner decides.
 *
 * @param personalForOtherRefused whether a personal book-in into another member's Lager is refused
 * @param ownerlessNeedsLogistician whether a book-in without a named owner needs Logistician+
 * @param ownerRefusal the refusal reason when the owner check fails
 * @param personalRefusal the refusal reason for a personal book-in into another member's Lager
 */
public record BookInRule(
    boolean personalForOtherRefused,
    boolean ownerlessNeedsLogistician,
    @NotNull String ownerRefusal,
    @NotNull String personalRefusal) {

  /**
   * The Lager's own Einbuchen: a personal book-in for another member is refused.
   *
   * @return the rule
   */
  public static @NotNull BookInRule lager() {
    return new BookInRule(
        true,
        false,
        "You are not allowed to create inventory items for other users",
        "You are not allowed to create personal inventory items for other users");
  }

  /**
   * The job-order production book-in: a personal book-in for another member is refused.
   *
   * @return the rule
   */
  public static @NotNull BookInRule production() {
    return new BookInRule(
        true,
        false,
        "You are not allowed to book produced stock in for this user",
        "You are not allowed to book produced stock into another user's personal inventory");
  }

  /**
   * The refinery store: a personal book-in for another member passes the owner check alone, and a
   * book-in without a named owner (an ownerless order) needs Logistician+.
   *
   * @return the rule
   */
  public static @NotNull BookInRule refineryStore() {
    String refusal = "Access denied: You are not allowed to store refinery output for other users";
    return new BookInRule(false, true, refusal, refusal);
  }
}
