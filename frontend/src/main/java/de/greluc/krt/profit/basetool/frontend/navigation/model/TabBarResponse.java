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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * One mode's phone tab-bar section as the backend stores it (REQ-UI-032).
 *
 * @param slots the three slots: an entry key for a chosen slot, {@code null} for an automatic one
 * @param version the section's optimistic-lock version, echoed on the next write
 */
@DtoMirror
public record TabBarResponse(@NotNull List<String> slots, long version) {

  /** The number of slots a member chooses. */
  public static final int SLOTS = 3;

  /** The section of a member who never edited the bar. */
  public static final TabBarResponse AUTOMATIC =
      new TabBarResponse(Collections.nCopies(SLOTS, null), 0);

  /**
   * Pads or cuts the slots to {@link #SLOTS} and copies them so the record stays immutable.
   *
   * @param slots the slots, possibly {@code null} or short
   * @param version the section's version
   */
  public TabBarResponse {
    List<String> fixed = new ArrayList<>(Collections.nCopies(SLOTS, null));
    if (slots != null) {
      for (int index = 0; index < Math.min(SLOTS, slots.size()); index++) {
        fixed.set(index, slots.get(index));
      }
    }
    slots = Collections.unmodifiableList(fixed);
  }
}
