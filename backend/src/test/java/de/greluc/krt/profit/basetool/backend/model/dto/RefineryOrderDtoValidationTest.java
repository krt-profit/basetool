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

package de.greluc.krt.profit.basetool.backend.model.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation tests for {@link RefineryOrderDto}: {@code expenses}, {@code otherExpenses} and
 * {@code oreSales} are optional but non-negative, and constraints cascade into the {@code goods}
 * elements.
 */
class RefineryOrderDtoValidationTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  /**
   * Returns {@code true} iff validating {@code dto} yields a constraint violation on {@code path}.
   */
  private static boolean hasViolationOn(RefineryOrderDto dto, String path) {
    return VALIDATOR.validate(dto).stream()
        .anyMatch(v -> v.getPropertyPath().toString().equals(path));
  }

  /**
   * Builds a DTO carrying only the {@code expenses} value under test; other fields are irrelevant.
   */
  private static RefineryOrderDto withExpenses(Double expenses) {
    return new RefineryOrderDto(
        null, null, null, null, null, null, expenses, null, null, null, null, null, null, null,
        null, null);
  }

  @Test
  void negativeExpenses_violatesPositiveOrZero() {
    assertTrue(
        hasViolationOn(withExpenses(-0.01), "expenses"),
        "negative expenses must be rejected (audit H-3) — it would inflate profit and payouts");
  }

  @Test
  void zeroExpenses_isAccepted() {
    assertFalse(hasViolationOn(withExpenses(0.0), "expenses"), "zero expenses must be allowed");
  }

  @Test
  void positiveExpenses_isAccepted() {
    assertFalse(
        hasViolationOn(withExpenses(1500.0), "expenses"), "positive expenses must be allowed");
  }

  @Test
  void nullExpenses_isAccepted() {
    assertFalse(
        hasViolationOn(withExpenses(null), "expenses"),
        "expenses is optional — null must be allowed, matching otherExpenses/oreSales");
  }

  /** Builds a DTO carrying exactly {@code good} in its {@code goods} list; other fields unset. */
  private static RefineryOrderDto withGood(RefineryGoodDto good) {
    return new RefineryOrderDto(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(good),
        null,
        null,
        null);
  }

  @Test
  void invalidGood_cascadesConstraintViolationIntoListElement() {
    RefineryGoodDto invalidGood = new RefineryGoodDto(null, null, 0, null, 1, null, null);
    assertTrue(
        hasViolationOn(withGood(invalidGood), "goods[0].inputQuantity"),
        "@Valid on the list element type must cascade @Min(1) into each RefineryGoodDto");
  }
}
