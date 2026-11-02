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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One mode's phone tab bar as the navigation renders it (REQ-UI-032): the three configurable slots
 * with the entry each one shows, and the choices their pickers offer.
 *
 * @param mode the mode whose bar this is
 * @param loaded whether the stored bar could be read; a failed read renders the defaults and
 *     disables the picker
 * @param version the tab-bar version the next write echoes
 * @param stored the three stored slots, {@code null} for an automatic one
 * @param slots the slots to show, at most three, in order
 * @param favouriteChoices the entries the pickers list first: the mode's available favourites
 * @param otherChoices the other available entries of the mode in registry order
 */
public record NavigationTabBar(
    @NotNull NavigationMode mode,
    boolean loaded,
    long version,
    @NotNull List<String> stored,
    @NotNull List<Slot> slots,
    @NotNull List<NavigationEntry> favouriteChoices,
    @NotNull List<NavigationEntry> otherChoices) {

  /**
   * Copies the lists so the record stays immutable.
   *
   * @param mode the mode
   * @param loaded whether the stored bar could be read
   * @param version the tab-bar version
   * @param stored the three stored slots
   * @param slots the slots to show
   * @param favouriteChoices the favourites the pickers list first
   * @param otherChoices the other choices
   */
  public NavigationTabBar {
    stored = Collections.unmodifiableList(new ArrayList<>(stored));
    slots = List.copyOf(slots);
    favouriteChoices = List.copyOf(favouriteChoices);
    otherChoices = List.copyOf(otherChoices);
  }

  /**
   * Whether no slot was ever chosen, so favourites and the defaults fill the bar.
   *
   * @return {@code true} iff every stored slot is empty
   */
  public boolean automatic() {
    return stored.stream().allMatch(Objects::isNull);
  }

  /**
   * The slot shown at a position.
   *
   * @param index the zero-based position
   * @return the slot, or {@code null} when nothing can fill the position
   */
  @Nullable
  public Slot slotAt(int index) {
    return slots.stream().filter(slot -> slot.index() == index).findFirst().orElse(null);
  }

  /**
   * The stored slots space-separated for the script that edits them; an automatic slot is a dash.
   *
   * @return the stored keys
   */
  @NotNull
  public String storedAttribute() {
    return stored.stream().map(key -> key == null ? "-" : key).collect(Collectors.joining(" "));
  }

  /**
   * The shown slots space-separated, which is what the first edit freezes; a position nothing fills
   * is a dash.
   *
   * @return the shown keys
   */
  @NotNull
  public String shownAttribute() {
    return IntStream.range(0, stored.size())
        .mapToObj(index -> slotAt(index) == null ? "-" : slotAt(index).entry().key())
        .collect(Collectors.joining(" "));
  }

  /**
   * One slot as the bar renders it.
   *
   * @param index the zero-based position
   * @param entry the entry the slot opens
   * @param icon the sprite icon name
   * @param path the path of the area the slot marks as current
   * @param standIn whether the slot shows a stand-in for a stored choice that is unavailable now
   */
  public record Slot(
      int index,
      @NotNull NavigationEntry entry,
      @NotNull String icon,
      @NotNull String path,
      boolean standIn) {}
}
