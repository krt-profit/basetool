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

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.SpecialCommandChange.Action;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest.StaffelChange;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean Validation tests that element constraints of {@link MembershipDeltaRequest}'s lists cascade
 * into the entries via the type-argument {@code @Valid}.
 */
class MembershipDeltaRequestValidationTest {

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
  void invalidStaffelChangeElementCascadesToViolation() {
    MembershipDeltaRequest req =
        new MembershipDeltaRequest(
            List.of(new StaffelChange(null, Boolean.FALSE, Boolean.FALSE)), null);

    Set<ConstraintViolation<MembershipDeltaRequest>> violations = validator.validate(req);

    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("squadronId")),
        "StaffelChange.squadronId @NotNull must be validated through List<@Valid StaffelChange>");
  }

  @Test
  void invalidSpecialCommandChangeElementCascadesToViolation() {
    MembershipDeltaRequest req =
        new MembershipDeltaRequest(
            null,
            List.of(
                new SpecialCommandChange(
                    UUID.randomUUID(), null, Boolean.FALSE, Boolean.FALSE, null)));

    Set<ConstraintViolation<MembershipDeltaRequest>> violations = validator.validate(req);

    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("action")),
        "SpecialCommandChange.action @NotNull must be validated through List<@Valid ...>");
  }

  @Test
  void wellFormedPayloadHasNoViolations() {
    MembershipDeltaRequest req =
        new MembershipDeltaRequest(
            List.of(new StaffelChange(UUID.randomUUID(), null, null)),
            List.of(new SpecialCommandChange(UUID.randomUUID(), Action.ADD, null, null, null)));

    assertTrue(validator.validate(req).isEmpty(), "a well-formed payload must have no violations");
  }
}
