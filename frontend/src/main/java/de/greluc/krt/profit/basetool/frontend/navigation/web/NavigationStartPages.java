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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAppearance;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAvailability;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * What the start-page controls render (REQ-UI-034): the pages each mode offers as a start page and
 * which one is chosen, for the header button and the selects of „Darstellung“.
 *
 * @param appearance the caller's stored appearance, which holds the start pages
 * @param member the pages the caller may choose as the member start page, in registry order
 * @param admin the pages the caller may choose as the administration start page
 */
public record NavigationStartPages(
    @NotNull NavigationAppearance appearance,
    @NotNull List<Option> member,
    @NotNull List<Option> admin) {

  /**
   * One page the caller may choose.
   *
   * @param key the entry key
   * @param labelKey the message key of the label
   */
  public record Option(@NotNull String key, @NotNull String labelKey) {}

  /**
   * Resolves the choices of a caller.
   *
   * @param registry the entry registry
   * @param access the caller's reachable roles and flags
   * @param appearance the caller's stored appearance
   * @return the choices; the lists are empty for a caller who may open no page of a mode
   */
  @NotNull
  public static NavigationStartPages of(
      @NotNull NavigationRegistry registry,
      @NotNull NavigationAccess access,
      @NotNull NavigationAppearance appearance) {
    List<NavigationEntry> offered =
        registry.entries().stream()
            .filter(entry -> registry.starrable(entry.key()).isPresent())
            .filter(entry -> registry.availability(entry, access) != NavigationAvailability.GONE)
            .toList();
    return new NavigationStartPages(
        appearance,
        offered.stream()
            .filter(entry -> !registry.isAdminEntry(entry.key()))
            .map(NavigationStartPages::option)
            .toList(),
        offered.stream()
            .filter(entry -> registry.isAdminEntry(entry.key()))
            .map(NavigationStartPages::option)
            .toList());
  }

  /**
   * The choices of a mode.
   *
   * @param modeSegment {@code member} or {@code admin}
   * @return the pages the caller may choose in that mode
   */
  @NotNull
  public List<Option> options(@NotNull String modeSegment) {
    return NavigationMode.ADMIN.getSegment().equals(modeSegment) ? admin : member;
  }

  /**
   * The mode an entry is a start page candidate of.
   *
   * @param entry a page or tab
   * @return {@code admin} for an entry the administration offers, else {@code member}
   */
  @NotNull
  public String modeOf(@NotNull NavigationEntry entry) {
    return admin.stream().anyMatch(option -> option.key().equals(entry.key()))
        ? NavigationMode.ADMIN.getSegment()
        : NavigationMode.MEMBER.getSegment();
  }

  /**
   * Whether an entry is the stored start page of its mode.
   *
   * @param entry a page or tab
   * @return {@code true} iff the entry is the chosen start page
   */
  public boolean isStartPage(@NotNull NavigationEntry entry) {
    return entry.key().equals(selected(modeOf(entry)));
  }

  /**
   * The stored start page of a mode.
   *
   * @param modeSegment {@code member} or {@code admin}
   * @return the entry key, or the empty string for the dashboard
   */
  @NotNull
  public String selected(@NotNull String modeSegment) {
    return appearance.startPageAttribute(
        NavigationMode.fromSegment(modeSegment).orElse(NavigationMode.MEMBER));
  }

  @NotNull
  private static Option option(@NotNull NavigationEntry entry) {
    return new Option(entry.key(), entry.labelKey());
  }
}
