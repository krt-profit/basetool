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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/**
 * Validator backing the frontend {@link WholeNumber} constraint: a {@link BigDecimal} is valid when
 * its fractional part is zero (tested by value via remainder modulo one, so {@code 500.00} passes
 * and {@code 500.50} fails). {@code null} passes — null-ness is enforced by {@code @NotNull}.
 */
public class WholeNumberValidator implements ConstraintValidator<WholeNumber, BigDecimal> {

  /**
   * Reports whether {@code value} is a whole number.
   *
   * @param value the amount under validation; {@code null} is treated as valid
   * @param context the constraint context (unused — the default message is sufficient)
   * @return {@code true} when {@code value} is {@code null} or has a zero fractional part
   */
  @Override
  public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
    return value == null || value.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0;
  }
}
