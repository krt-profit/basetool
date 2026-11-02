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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

/**
 * The {@code storage} option of the Lager tab ({@code /inventory}): the personal list or the global
 * list shown instead of the aggregated overview (REQ-UI-026).
 */
final class LagerStorage {

  /** The query parameter that selects the option. */
  static final String PARAM = "storage";

  /** The value that selects the personal list. */
  static final String MY = "my";

  /** The value that selects the global list. */
  static final String ALL = "all";

  /** The request condition of the personal list. */
  static final String MY_CONDITION = PARAM + "=" + MY;

  /** The request condition of the global list. */
  static final String ALL_CONDITION = PARAM + "=" + ALL;

  /** The Lager tab with the personal list selected. */
  static final String MY_PATH = "/inventory?" + MY_CONDITION;

  /** The Lager tab with the global list selected. */
  static final String ALL_PATH = "/inventory?" + ALL_CONDITION;

  private LagerStorage() {}
}
