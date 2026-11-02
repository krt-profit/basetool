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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** A per-request fact besides the roles that a {@link NavigationGate} can require or exclude. */
public enum NavigationFlag {

  /** The caller may open the org-unit blueprint availability overview. */
  BLUEPRINT_OVERVIEW,

  /** The caller may enter the Job-Order area. */
  JOB_ORDERS,

  /** The caller may view the orders their own org unit requested (REQ-ORDERS-023). */
  OWN_JOB_ORDERS,

  /** The caller holds an assignee entry on at least one order (REQ-ORDERS-040). */
  ASSIGNED_JOB_ORDERS,

  /** The caller holds a Logistician grant in the active context. */
  LOGISTICIAN_HERE,

  /** The caller holds officer reach in at least one unit, whatever unit is pinned. */
  OFFICER_ANYWHERE,

  /** The Leitung page is offered in the active context (officer reach). */
  OPEN_LEITUNG,

  /** Promotion management is offered in the active context (officer reach). */
  MANAGE_PROMOTIONS,

  /** The supply surface is on (REQ-SUP-001). */
  SUPPLY,

  /** The purchasing surface is on (REQ-PUR-001). */
  PURCHASING,

  /** The promotion subsystem is exposed in the active context. */
  PROMOTION,

  /** The page is rendered in German. */
  LANGUAGE_DE,

  /** The page is rendered in English. */
  LANGUAGE_EN;

  /**
   * Whether the right behind this flag is still held in some context when the set of per-request
   * flags lacks it: a right not tied to the pinned unit holds user-wide only when the flag is set,
   * a right tied to the pinned unit holds while its user-wide counterpart is set, and a switch of
   * the installation or the page never counts as a lost right.
   *
   * @param flags the per-request flags that are set
   * @return {@code false} iff the caller lost the right in every context
   */
  public boolean holdsUserWide(@NotNull Set<NavigationFlag> flags) {
    return switch (this) {
      case BLUEPRINT_OVERVIEW, JOB_ORDERS, OWN_JOB_ORDERS, ASSIGNED_JOB_ORDERS, OFFICER_ANYWHERE ->
          flags.contains(this);
      case OPEN_LEITUNG, MANAGE_PROMOTIONS -> flags.contains(OFFICER_ANYWHERE);
      case LOGISTICIAN_HERE, SUPPLY, PURCHASING, PROMOTION, LANGUAGE_DE, LANGUAGE_EN -> true;
    };
  }
}
