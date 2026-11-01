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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

/** The bounds the navigation preferences are validated against (REQ-UI-032). */
public final class NavigationLimits {

  /** The most favourites a member keeps, shared by both navigation modes. */
  public static final int MAX_FAVOURITES = 12;

  /** The most hidden pages one request stores; bounds the input, the registry holds fewer. */
  public static final int MAX_HIDDEN = 100;

  /** The longest entry key the store accepts. */
  public static final int MAX_ENTRY_KEY_LENGTH = 64;

  /** The format of an entry key: lower-case dot-separated segments, e.g. {@code missions.list}. */
  public static final String ENTRY_KEY_PATTERN = "^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$";

  /** The longest mode tag the order table stores. */
  public static final int MAX_MODE_LENGTH = 8;

  /** The format of a group, link or scope key of an order: dot- or hyphen-separated segments. */
  public static final String ORDER_KEY_PATTERN = "^[a-z][a-z0-9]*([.-][a-z0-9]+)*$";

  /** The most keys one order scope stores, the links of one group or the groups of one mode. */
  public static final int MAX_ORDER_KEYS = 100;

  /** The most scopes one mode's order stores, bounding what the caller can accumulate. */
  public static final int MAX_ORDER_SCOPES = 32;

  /** The most keys one prune request names. */
  public static final int MAX_PRUNE_KEYS = 200;

  private NavigationLimits() {}
}
