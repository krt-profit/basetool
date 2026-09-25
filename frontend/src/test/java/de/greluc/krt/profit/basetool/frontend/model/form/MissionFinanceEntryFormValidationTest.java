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

package de.greluc.krt.profit.basetool.frontend.model.form;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.frontend.model.dto.FinanceType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation tests for {@link MissionFinanceEntryForm}, the create/edit form backing the
 * mission-finance modals. They pin the whole-aUEC input rule (REQ-MISSION-001): the form rejects a
 * fractional amount before the controller ever calls the backend, so the user gets an inline field
 * error instead of a 400 round-trip. Zero is accepted — finance amounts are whole, not strictly
 * positive.
 */
class MissionFinanceEntryFormValidationTest {

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

  private static MissionFinanceEntryForm form(BigDecimal amount) {
    MissionFinanceEntryForm form = new MissionFinanceEntryForm();
    form.setType(FinanceType.INCOME);
    form.setAmount(amount);
    return form;
  }

  @Test
  void wholeAmountHasNoViolations() {
    assertTrue(validator.validate(form(new BigDecimal("250"))).isEmpty());
  }

  @Test
  void fractionalAmountIsRejected() {
    Set<ConstraintViolation<MissionFinanceEntryForm>> violations =
        validator.validate(form(new BigDecimal("250.5")));

    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("amount")));
  }

  @Test
  void zeroAmountIsAllowed() {
    assertTrue(validator.validate(form(BigDecimal.ZERO)).isEmpty());
  }
}
