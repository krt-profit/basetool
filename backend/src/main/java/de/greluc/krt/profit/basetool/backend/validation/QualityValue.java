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

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import de.greluc.krt.profit.basetool.backend.kernel.Quality;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Asserts that a quality or a quality floor lies in {@link Quality#MIN} to {@link Quality#MAX}
 * (REQ-DATA-023). {@code null} is valid; combine with {@code @NotNull} where a value is required.
 */
@Documented
@Min(Quality.MIN)
@Max(Quality.MAX)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface QualityValue {

  /**
   * The violation message template surfaced when the value lies outside the range.
   *
   * @return the message template (resolved against {@code ValidationMessages})
   */
  String message() default "{error.validation.quality_out_of_range}";

  /**
   * The validation groups this constraint participates in.
   *
   * @return the constraint groups (empty by default)
   */
  Class<?>[] groups() default {};

  /**
   * Carrier for custom payload metadata attached to the constraint.
   *
   * @return the payload types (empty by default)
   */
  Class<? extends Payload>[] payload() default {};
}
