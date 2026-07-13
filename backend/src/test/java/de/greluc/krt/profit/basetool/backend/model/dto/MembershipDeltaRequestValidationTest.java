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
 * Bean Validation contract tests for {@link MembershipDeltaRequest}. The two list components
 * declare {@code List<@Valid StaffelChange>} / {@code List<@Valid SpecialCommandChange>} so
 * element-level constraints cascade into the entries; these tests guard that cascade, which broke
 * silently if the {@code @Valid} were placed on the container (the deprecated {@code @Valid
 * List<...>} form, HV000271) instead of the type argument.
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
    // Given a staffeln entry that violates @NotNull squadronId
    MembershipDeltaRequest req =
        new MembershipDeltaRequest(
            List.of(new StaffelChange(null, Boolean.FALSE, Boolean.FALSE)), null);

    // When
    Set<ConstraintViolation<MembershipDeltaRequest>> violations = validator.validate(req);

    // Then the element-level constraint is reported via the cascade
    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("squadronId")),
        "StaffelChange.squadronId @NotNull must be validated through List<@Valid StaffelChange>");
  }

  @Test
  void invalidSpecialCommandChangeElementCascadesToViolation() {
    // Given an SK entry that violates @NotNull action
    MembershipDeltaRequest req =
        new MembershipDeltaRequest(
            null,
            List.of(
                new SpecialCommandChange(
                    UUID.randomUUID(), null, Boolean.FALSE, Boolean.FALSE, null)));

    // When
    Set<ConstraintViolation<MembershipDeltaRequest>> violations = validator.validate(req);

    // Then the element-level constraint is reported via the cascade
    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("action")),
        "SpecialCommandChange.action @NotNull must be validated through List<@Valid ...>");
  }

  @Test
  void wellFormedPayloadHasNoViolations() {
    // Given a valid delta touching both sides
    MembershipDeltaRequest req =
        new MembershipDeltaRequest(
            List.of(new StaffelChange(UUID.randomUUID(), null, null)),
            List.of(new SpecialCommandChange(UUID.randomUUID(), Action.ADD, null, null, null)));

    // When / Then
    assertTrue(validator.validate(req).isEmpty(), "a well-formed payload must have no violations");
  }
}
