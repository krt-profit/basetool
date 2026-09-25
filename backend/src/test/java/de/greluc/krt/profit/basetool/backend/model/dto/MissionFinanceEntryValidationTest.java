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

import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation contract tests for the mission-finance write DTOs. They pin the whole-aUEC rule
 * (REQ-MISSION-001): an operator-entered finance amount must be a whole number, so the
 * {@code @Digits(fraction = 0)} constraint rejects fractional input at the {@code @Valid} boundary
 * and a non-browser API client cannot store sub-aUEC precision. Zero stays valid — finance amounts
 * are whole, not strictly positive, so the existing {@code @DecimalMin("0.0")} lower bound is kept.
 */
class MissionFinanceEntryValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void initValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeFactory() {
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void wholeCreateAmountHasNoViolations() {
    MissionFinanceEntryCreateDto dto =
        new MissionFinanceEntryCreateDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ore sale",
            FinanceType.INCOME,
            new BigDecimal("250"));

    assertTrue(validator.validate(dto).isEmpty());
  }

  @Test
  void zeroCreateAmountIsAllowed() {
    MissionFinanceEntryCreateDto dto =
        new MissionFinanceEntryCreateDto(
            UUID.randomUUID(), UUID.randomUUID(), null, FinanceType.EXPENSE, BigDecimal.ZERO);

    assertTrue(validator.validate(dto).isEmpty());
  }

  @Test
  void fractionalCreateAmountIsRejected() {
    MissionFinanceEntryCreateDto dto =
        new MissionFinanceEntryCreateDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            FinanceType.INCOME,
            new BigDecimal("250.5"));

    Set<ConstraintViolation<MissionFinanceEntryCreateDto>> violations = validator.validate(dto);

    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
  }

  @Test
  void negativeCreateAmountIsRejected() {
    MissionFinanceEntryCreateDto dto =
        new MissionFinanceEntryCreateDto(
            UUID.randomUUID(), UUID.randomUUID(), null, FinanceType.EXPENSE, new BigDecimal("-5"));

    Set<ConstraintViolation<MissionFinanceEntryCreateDto>> violations = validator.validate(dto);

    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
  }

  @Test
  void fractionalUpdateAmountIsRejected() {
    MissionFinanceEntryUpdateDto dto =
        new MissionFinanceEntryUpdateDto(null, FinanceType.INCOME, new BigDecimal("99.99"), 1L);

    Set<ConstraintViolation<MissionFinanceEntryUpdateDto>> violations = validator.validate(dto);

    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
  }

  @Test
  void wholeUpdateAmountHasNoViolations() {
    MissionFinanceEntryUpdateDto dto =
        new MissionFinanceEntryUpdateDto(
            "repairs", FinanceType.EXPENSE, new BigDecimal("1000"), 3L);

    assertTrue(validator.validate(dto).isEmpty());
  }
}
