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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Parses exact whole-aUEC values at the boundary of the order's monetary model. */
final class WholeAuec {

  private static final Pattern WHOLE = Pattern.compile("[0-9]+(?:[.]0+)?");
  // The integer capacity of an unconstrained PostgreSQL NUMERIC.
  private static final int MAX_INTEGER_DIGITS = 131_072;
  private static final String POSITIVE_WHOLE = "orders.money.amount.wholePositive";

  private WholeAuec() {}

  static BigDecimal positive(String input) {
    if (!WHOLE.matcher(input).matches()) {
      throw new BadRequestException(POSITIVE_WHOLE);
    }
    int separator = input.indexOf('.');
    int end = separator < 0 ? input.length() : separator;
    int start = 0;
    while (start < end && input.charAt(start) == '0') {
      start++;
    }
    if (start == end) {
      throw new BadRequestException(POSITIVE_WHOLE);
    }
    if (end - start > MAX_INTEGER_DIGITS) {
      throw new BadRequestException("orders.money.amount.storageRange");
    }
    return new BigDecimal(input.substring(start, end));
  }
}
