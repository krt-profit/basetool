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
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** Parses exact whole-aUEC values at the boundary of the order's monetary model. */
final class WholeAuec {

  private static final Pattern WHOLE = Pattern.compile("[0-9]+(?:[.]0+)?");
  // The integer capacity of an unconstrained PostgreSQL NUMERIC.
  private static final int MAX_INTEGER_DIGITS = 131_072;
  private static final String POSITIVE_WHOLE = "orders.money.amount.wholePositive";
  private static final String STORAGE_RANGE = "orders.money.amount.storageRange";

  private WholeAuec() {}

  static BigDecimal positive(String input) {
    BigDecimal amount = parse(input, POSITIVE_WHOLE);
    if (amount.signum() == 0) {
      throw new BadRequestException(POSITIVE_WHOLE);
    }
    return amount;
  }

  /**
   * Checks a decimal amount as a positive whole aUEC value, with the refusals of the text entry.
   *
   * @param amount the amount
   * @return the amount at scale zero
   * @throws BadRequestException when the amount is not positive, has a fraction or exceeds the
   *     integer capacity of the stored amounts
   */
  static BigDecimal positive(BigDecimal amount) {
    if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
      throw new BadRequestException(POSITIVE_WHOLE);
    }
    BigDecimal whole = amount.setScale(0, RoundingMode.UNNECESSARY);
    if (whole.precision() > MAX_INTEGER_DIGITS) {
      throw new BadRequestException(STORAGE_RANGE);
    }
    return whole;
  }

  static BigDecimal nonnegative(String input) {
    return parse(input, "orders.money.amount.wholeNonnegative");
  }

  private static BigDecimal parse(String input, String malformed) {
    if (!WHOLE.matcher(input).matches()) {
      throw new BadRequestException(malformed);
    }
    int separator = input.indexOf('.');
    int end = separator < 0 ? input.length() : separator;
    int start = 0;
    while (start < end && input.charAt(start) == '0') {
      start++;
    }
    if (start == end) {
      return BigDecimal.ZERO;
    }
    if (end - start > MAX_INTEGER_DIGITS) {
      throw new BadRequestException(STORAGE_RANGE);
    }
    return new BigDecimal(input.substring(start, end));
  }
}
