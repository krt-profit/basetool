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

package de.greluc.krt.profit.basetool.backend.inventory.api.events;

import java.math.BigDecimal;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One lot of a Lager transfer as a notification shows it: what moved, how much, at which quality
 * and where (REQ-INV-055).
 *
 * @param name the material's or game item's catalogue name
 * @param amount the transferred amount
 * @param piece whether the amount counts pieces rather than SCU
 * @param quality the lot's quality, or {@code null} when the row carries none
 * @param location the location shown with the lot, or {@code null} when the row has none
 */
public record TransferredLot(
    @NotNull String name,
    double amount,
    boolean piece,
    @Nullable Integer quality,
    @Nullable String location) {

  /** How many lots a notification lists before it ends the list with an ellipsis. */
  public static final int MAX_LISTED = 20;

  /** Separator between two listed lots. */
  private static final String SEPARATOR = "; ";

  /**
   * Words the lot language-neutrally, e.g. {@code 12 SCU Quantanium (Q800) in Area18} or {@code 3×
   * Medpen in Lorville}.
   *
   * @return the lot as one line of a notification
   */
  @NotNull
  public String format() {
    StringBuilder line = new StringBuilder();
    String quantity = BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    if (piece) {
      line.append(quantity).append("× ").append(name);
    } else {
      line.append(quantity).append(" SCU ").append(name);
    }
    if (quality != null) {
      line.append(" (Q").append(quality).append(')');
    }
    if (location != null && !location.isBlank()) {
      line.append(" in ").append(location);
    }
    return line.toString();
  }

  /**
   * Words a list of lots for the {@code lots} render parameter: the first {@link #MAX_LISTED} lots
   * joined by semicolons, followed by an ellipsis when more were transferred.
   *
   * @param lots the transferred lots, in booking order
   * @return the joined lots; empty for an empty list
   */
  @NotNull
  public static String formatAll(@NotNull List<TransferredLot> lots) {
    StringBuilder joined = new StringBuilder();
    int listed = Math.min(lots.size(), MAX_LISTED);
    for (int i = 0; i < listed; i++) {
      if (i > 0) {
        joined.append(SEPARATOR);
      }
      joined.append(lots.get(i).format());
    }
    if (lots.size() > MAX_LISTED) {
      joined.append(SEPARATOR).append('…');
    }
    return joined.toString();
  }
}
