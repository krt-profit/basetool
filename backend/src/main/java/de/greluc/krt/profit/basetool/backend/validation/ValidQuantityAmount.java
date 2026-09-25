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

package de.greluc.krt.profit.basetool.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint for {@link QuantityAware} DTOs: {@code amount > 0}, and a whole number for
 * {@code PIECE} materials and game items. {@code SCU} precision is not rejected (it is rounded on
 * persist); {@code null} fields are left to {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = ValidQuantityAmountValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidQuantityAmount {
  /**
   * Returns message key resolved from {@code messages*.properties}; defaults to a generic invalid-
   * amount message and is overridden per failure with a more specific key (positive / integer).
   *
   * @return message key resolved from {@code messages*.properties}; defaults to a generic invalid-
   *     amount message and is overridden per failure with a more specific key (positive / integer)
   */
  String message() default "{error.validation.quantity_amount_invalid}";

  /**
   * Returns validation groups (required by the Bean Validation spec; not used in this project).
   *
   * @return validation groups (required by the Bean Validation spec; not used in this project)
   */
  Class<?>[] groups() default {};

  /**
   * Returns payload classes (required by the Bean Validation spec; not used in this project).
   *
   * @return payload classes (required by the Bean Validation spec; not used in this project)
   */
  Class<? extends Payload>[] payload() default {};
}
