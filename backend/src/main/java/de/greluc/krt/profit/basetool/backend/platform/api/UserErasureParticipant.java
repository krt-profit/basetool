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

package de.greluc.krt.profit.basetool.backend.platform.api;

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A module's part of the hard deletion of an {@code app_user} row (REQ-DATA-008): it removes,
 * reassigns or unlinks the rows its module holds for the departing member, inside the deletion's
 * transaction.
 *
 * <p>The orchestrator runs every participant by {@link Phase}, then by {@link #position()}, and
 * writes the deletion's audit rows from the counts returned. Implementations join the deletion's
 * transaction ({@code MANDATORY}) and issue set-based statements without {@code
 * clearAutomatically}.
 */
@ObserverSpi
public interface UserErasureParticipant {

  /** The stage of the deletion a participant works in; the constants are in the order they run. */
  enum Phase {

    /** Removes the member's rows. */
    DELETE,

    /** Hands shared aggregates the member owns to the fallback admin. */
    REASSIGN,

    /** Clears the member's back-references on rows that stay. */
    UNLINK
  }

  /** The row counts the deletion's audit rows report. */
  enum Erased {

    /** Lager rows removed. */
    INVENTORY_ROWS,

    /** Ships removed. */
    SHIPS,

    /** "Mein Inventar" rows removed. */
    PERSONAL_INVENTORY,

    /** Personal blueprints removed. */
    BLUEPRINTS,

    /** Notifications removed. */
    NOTIFICATIONS,

    /** Notification-rule selectors removed. */
    RULE_SELECTORS,

    /** Promotion evaluations removed. */
    EVALUATIONS,

    /** Refinery orders reassigned. */
    REFINERY_ORDERS_REASSIGNED
  }

  /**
   * Returns the stage this participant works in.
   *
   * @return the phase
   */
  @NotNull
  Phase phase();

  /**
   * Returns where the participant runs within its phase, ascending; the order of statements the
   * deletion issues is part of its contract, and two participants of a phase never share a value.
   *
   * @return the position within the phase
   */
  int position();

  /**
   * Erases the member's rows of this module.
   *
   * @param userId the departing member's {@code app_user.id}
   * @param fallbackAdminId the admin that takes over shared aggregates; never the member
   * @return the counts to report, empty when the step reports none
   */
  @NotNull
  Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId);
}
