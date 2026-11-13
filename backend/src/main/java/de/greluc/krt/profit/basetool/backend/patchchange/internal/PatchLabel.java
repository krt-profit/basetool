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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The label of a patch change: a version of two or three numbers such as {@code 4.4} or {@code
 * 4.4.1} (REQ-PATCH-001). Labels compare number by number, a missing third number counting as zero.
 *
 * @param value the label as written
 */
public record PatchLabel(@NotNull String value) {

  /**
   * Two or three dot-separated numbers of at most nine digits, inside the column's 32 characters.
   */
  private static final Pattern SHAPE = Pattern.compile("^\\d{1,9}(\\.\\d{1,9}){1,2}$");

  /** The numbers a label is compared on: major, minor and patch. */
  private static final int PARTS = 3;

  /** The separator between a label's numbers. */
  private static final Pattern DOT = Pattern.compile("\\.");

  /**
   * Validates and wraps a label.
   *
   * @param value the label as the admin wrote it
   * @return the label
   * @throws BadRequestException {@code error.patch_change.label_invalid} for any other shape
   */
  @Contract("null -> fail")
  public static @NotNull PatchLabel parse(@Nullable String value) {
    if (value == null || !SHAPE.matcher(value).matches()) {
      throw new BadRequestException("error.patch_change.label_invalid");
    }
    return new PatchLabel(value);
  }

  /**
   * Tells whether this label names a later patch than another.
   *
   * @param other the other label
   * @return {@code true} when this label's numbers are higher, the first difference deciding
   */
  public boolean isAfter(@NotNull PatchLabel other) {
    return Arrays.compare(numbers(), other.numbers()) > 0;
  }

  /**
   * Reads the label's numbers, padded with zero to major, minor and patch.
   *
   * @return the three numbers
   */
  private long @NotNull [] numbers() {
    long[] numbers = new long[PARTS];
    int part = 0;
    for (String number : DOT.splitAsStream(value).toList()) {
      numbers[part++] = Long.parseLong(number);
    }
    return numbers;
  }
}
