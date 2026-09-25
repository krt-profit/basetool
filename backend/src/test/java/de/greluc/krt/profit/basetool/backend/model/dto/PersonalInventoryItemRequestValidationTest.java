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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation contract tests for the personal inventory write DTOs. Failures here would
 * silently allow malformed payloads through the {@code @Valid}-annotated controller methods – treat
 * any new failure as a regression.
 */
class PersonalInventoryItemRequestValidationTest {

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
  void validCreateRequestShouldHaveNoViolations() {
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            "Medkit", "first aid", 42, PersonalInventoryLocationType.CITY, 3);

    assertTrue(validator.validate(req).isEmpty());
  }

  @Test
  void blankNameShouldBeRejected() {
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            "  ", null, 1, PersonalInventoryLocationType.CITY, 1);

    Set<ConstraintViolation<PersonalInventoryItemCreateRequest>> violations =
        validator.validate(req);

    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("name")));
  }

  @Test
  void overlongNameShouldBeRejected() {
    String tooLong = "x".repeat(121);
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            tooLong, null, 1, PersonalInventoryLocationType.CITY, 1);

    assertFalse(validator.validate(req).isEmpty());
  }

  @Test
  void overlongNoteShouldBeRejected() {
    String tooLong = "x".repeat(2001);
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            "ok", tooLong, 1, PersonalInventoryLocationType.CITY, 1);

    assertFalse(validator.validate(req).isEmpty());
  }

  @Test
  void quantityBelowOneShouldBeRejected() {
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest(
            "ok", null, 1, PersonalInventoryLocationType.CITY, 0);

    Set<ConstraintViolation<PersonalInventoryItemCreateRequest>> violations =
        validator.validate(req);

    assertFalse(violations.isEmpty());
    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("quantity")));
  }

  @Test
  void missingLocationFieldsShouldBeRejected() {
    PersonalInventoryItemCreateRequest req =
        new PersonalInventoryItemCreateRequest("ok", null, null, null, 1);

    Set<ConstraintViolation<PersonalInventoryItemCreateRequest>> violations =
        validator.validate(req);

    assertEquals(2, violations.size());
  }

  @Test
  void updateRequestRequiresVersion() {
    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "ok", null, 1, PersonalInventoryLocationType.CITY, 1, null);

    Set<ConstraintViolation<PersonalInventoryItemUpdateRequest>> violations =
        validator.validate(req);

    assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("version")));
  }

  @Test
  void validUpdateRequestShouldPass() {
    PersonalInventoryItemUpdateRequest req =
        new PersonalInventoryItemUpdateRequest(
            "ok", null, 1, PersonalInventoryLocationType.CITY, 1, 0L);

    assertTrue(validator.validate(req).isEmpty());
  }
}
