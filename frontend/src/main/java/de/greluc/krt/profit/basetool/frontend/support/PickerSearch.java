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
 * Page sizes for the searchable comboboxes' server-side catalogue searches (REQ-FE-016).
 *
 * <p><b>The one rule these constants encode: a picker relay MUST fetch strictly more rows than the
 * combobox renders.</b> {@code krt-searchable-select.js} renders at most {@link #RENDER_CAP} rows
 * and decides whether to show the "keep typing to narrow the list" hint with {@code matches.length
 * > maxResults} — a comparison against the rows it actually received. A relay that fetches the
 * render cap or fewer therefore makes that condition <em>unsatisfiable</em>: the hint can never
 * render, and every match past the fetched page is invisible with nothing on screen saying so. That
 * is precisely the silent cap REQ-FE-016 and ADR-0104 forbid, and it shipped: the location relay
 * fetched 25 rows against a render cap of 50, so 28 of the 53 visible locations — MIC-L5, Patch
 * City, New Babbage, Orison among them — could not be booked into the Lager at all unless the user
 * guessed a search term.
 *
 * <p>The {@code + 1} in the page sizes below is an overflow sentinel, not a display bound: the
 * extra row is never rendered, it exists only so the component can tell "there are more matches"
 * from "that was all of them" and say so.
 *
 * <p>{@link #RENDER_CAP} and {@link #LOCATION_RENDER_CAP} mirror values that live in the browser
 * ({@code krt-searchable-select.js}'s {@code maxResults} default and the {@code remote-locations}
 * entry of {@code krtComboboxI18n.kinds} in {@code fragments/head.html}). {@code
 * PickerSearchLimitsParityTest} reads both files off the classpath and pins them against these
 * constants, so the two halves cannot drift back apart silently.
 */
public final class PickerSearch {

  /**
   * How many matches the combobox renders at most — mirrors the {@code maxResults} default in
   * {@code krt-searchable-select.js}. Every relay below fetches more than this so an overflow is
   * detectable.
   */
  public static final int RENDER_CAP = 50;

  /**
   * Page size for the open-ended catalogue relays (materials, bookable game items, bank accounts):
   * one more than {@link #RENDER_CAP}, so the combobox renders a full page and announces the rest
   * via the hint. These catalogues run to thousands of rows, so browsing them whole is not the goal
   * — typing is, and the hint is what makes that discoverable.
   */
  public static final int PAGE_SIZE = RENDER_CAP + 1;

  /**
   * Render cap for the location picker, raised above {@link #RENDER_CAP} because the location
   * catalogue is small and bounded by the game universe (53 visible rows in production, 64
   * including hidden ones — one row per live UEX city / space station plus admin-curated entries).
   * A booking user expects to scroll a list that short rather than guess a search term, so the
   * whole catalogue is rendered; the hint still takes over should it ever outgrow this bound.
   */
  public static final int LOCATION_RENDER_CAP = 200;

  /**
   * Page size for {@code /catalog/location-search}: one more than {@link #LOCATION_RENDER_CAP}, on
   * the same overflow-sentinel rule as {@link #PAGE_SIZE}.
   */
  public static final int LOCATION_PAGE_SIZE = LOCATION_RENDER_CAP + 1;

  private PickerSearch() {}
}
