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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Reads {@code source.build.changeNumber}, a decimal integer written as text that orders Builds
 * within a Channel and is compared as a number, never as text (REQ-P4K-004).
 */
public final class P4kChangeNumber {

  /** A decimal integer that fits a {@code long}. */
  private static final Pattern DECIMAL = Pattern.compile("^\\d{1,18}$");

  /** Non-instantiable holder of static helpers. */
  private P4kChangeNumber() {}

  /**
   * Parses a change number.
   *
   * @param text the change number text, or {@code null}
   * @return the number, or {@code null} when absent or not a decimal integer of up to 18 digits
   */
  @Contract("null -> null")
  @Nullable
  public static Long parse(@Nullable String text) {
    if (text == null || !DECIMAL.matcher(text).matches()) {
      return null;
    }
    return Long.parseLong(text);
  }
}
