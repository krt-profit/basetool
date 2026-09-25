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

package de.greluc.krt.profit.basetool.frontend.support;

/**
 * Page sizes for the searchable comboboxes' server-side searches (REQ-FE-016).
 *
 * <p>A relay must fetch strictly more rows than the combobox renders, so {@code
 * krt-searchable-select.js} can show its "keep typing" hint; the {@code + 1} is that overflow
 * sentinel. {@link #RENDER_CAP} and {@link #LOCATION_RENDER_CAP} mirror browser-side values,
 * checked by {@code PickerSearchLimitsParityTest}.
 */
public final class PickerSearch {

  /**
   * How many matches the combobox renders at most — mirrors the {@code maxResults} default in
   * {@code krt-searchable-select.js}. Every relay below fetches more than this so an overflow is
   * detectable.
   */
  public static final int RENDER_CAP = 50;

  /**
   * Page size for the open-ended catalogue relays and the user searches: {@link #RENDER_CAP} plus
   * the overflow sentinel.
   */
  public static final int PAGE_SIZE = RENDER_CAP + 1;

  /**
   * Render cap for the location picker, above {@link #RENDER_CAP} so the whole small location
   * catalogue is listed.
   */
  public static final int LOCATION_RENDER_CAP = 200;

  /**
   * Page size for {@code /catalog/location-search}: one more than {@link #LOCATION_RENDER_CAP}, on
   * the same overflow-sentinel rule as {@link #PAGE_SIZE}.
   */
  public static final int LOCATION_PAGE_SIZE = LOCATION_RENDER_CAP + 1;

  private PickerSearch() {}
}
