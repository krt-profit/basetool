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
 * Bean Validation contract tests for the {@code bookIn} requirement on {@link
 * JobOrderItemProductionCreateDto} (REQ-INV-032 flip): the transitional null-tolerant rollout
 * window closed when the production modal shipped its book-in section, so a payload without the
 * block — or without the block's required {@code locationId} — must surface as a 400 validation
 * error at the {@code @Valid} controller boundary, never reach {@code
 * JobOrderItemProductionService}. The former service-level "null bookIn = legacy no-op" test lives
 * on here as the validation-rejection contract.
 */
class JobOrderItemProductionCreateDtoValidationTest {

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

  /**
   * Builds a production payload with a fixed valid amount/version/consumption and the given book-in
   * block.
   *
   * @param bookIn the book-in block under test, or {@code null}
   * @return the assembled payload
   */
  private static JobOrderItemProductionCreateDto dto(
      JobOrderItemProductionCreateDto.BookInDto bookIn) {
    return new JobOrderItemProductionCreateDto(1, 1L, List.of(), List.of(), bookIn);
  }

  // covers REQ-INV-032 (missing bookIn -> 400 validation, the flipped rollout contract)
  @Test
  void missingBookIn_isRejected() {
    Set<ConstraintViolation<JobOrderItemProductionCreateDto>> violations =
        validator.validate(dto(null));

    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("bookIn")),
        "a payload without a bookIn block must violate the @NotNull bookIn contract");
  }

  // covers REQ-INV-032 (bookIn.locationId stays required inside the cascaded block)
  @Test
  void bookInWithoutLocation_isRejected() {
    Set<ConstraintViolation<JobOrderItemProductionCreateDto>> violations =
        validator.validate(
            dto(
                new JobOrderItemProductionCreateDto.BookInDto(
                    null, UUID.randomUUID(), null, false, true)));

    assertTrue(
        violations.stream()
            .anyMatch(v -> v.getPropertyPath().toString().equals("bookIn.locationId")),
        "a bookIn block without a locationId must violate the cascaded @NotNull contract");
  }

  // covers REQ-INV-032 (a complete bookIn block passes the boundary validation)
  @Test
  void completeBookIn_isAccepted() {
    Set<ConstraintViolation<JobOrderItemProductionCreateDto>> violations =
        validator.validate(
            dto(
                new JobOrderItemProductionCreateDto.BookInDto(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false, true)));

    assertTrue(violations.isEmpty(), "a complete payload must produce no violations");
  }
}
