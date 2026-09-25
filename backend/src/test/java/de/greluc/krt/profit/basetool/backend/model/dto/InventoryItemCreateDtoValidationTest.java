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

import de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmountValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
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
 * Bean Validation tests for the catalog guards of {@link InventoryItemCreateDto} (REQ-INV-029/031):
 * material/game-item XOR, quality by kind, and no mission for a game item.
 *
 * <p>The validator factory supplies {@link ValidQuantityAmountValidator} with a stub lookup that
 * treats every material as SCU.
 */
class InventoryItemCreateDtoValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void initValidator() {
    factory =
        Validation.byDefaultProvider()
            .configure()
            .constraintValidatorFactory(
                new ConstraintValidatorFactory() {
                  @Override
                  public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                    if (key == ValidQuantityAmountValidator.class) {
                      return key.cast(new ValidQuantityAmountValidator(materialId -> false));
                    }
                    try {
                      return key.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                      throw new IllegalStateException(
                          "Cannot instantiate constraint validator " + key.getName(), e);
                    }
                  }

                  @Override
                  public void releaseInstance(ConstraintValidator<?, ?> instance) {}
                })
            .buildValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeFactory() {
    if (factory != null) {
      factory.close();
    }
  }

  /**
   * Builds a create payload with the given catalog, quality and mission fields and fixed valid
   * values elsewhere.
   *
   * @param materialId the material reference, or {@code null}
   * @param gameItemId the game-item reference, or {@code null}
   * @param quality the quality grade, or {@code null}
   * @param missionId the single mission reference, or {@code null}
   * @param missionAllocations the mission split list, or {@code null}
   * @return the assembled payload
   */
  private static InventoryItemCreateDto dto(
      UUID materialId,
      UUID gameItemId,
      Integer quality,
      UUID missionId,
      List<InventoryAllocationInput> missionAllocations) {
    return new InventoryItemCreateDto(
        null,
        materialId,
        gameItemId,
        UUID.randomUUID(),
        quality,
        5.0,
        false,
        missionId,
        null,
        null,
        null,
        null,
        missionAllocations);
  }

  /**
   * Asserts that validating {@code payload} reports a violation on the given {@code @AssertTrue}
   * property.
   *
   * @param payload the payload to validate
   * @param property the guard's bean-property name
   */
  private static void assertViolationOn(InventoryItemCreateDto payload, String property) {
    Set<ConstraintViolation<InventoryItemCreateDto>> violations = validator.validate(payload);
    assertTrue(
        violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(property)),
        "expected a violation on " + property + " but got: " + violations);
  }

  @Test
  void neitherCatalogReference_violatesXorGuard() {
    InventoryItemCreateDto payload = dto(null, null, null, null, null);

    assertViolationOn(payload, "catalogReferenceValid");
    assertTrue(
        validator.validate(payload).stream()
            .noneMatch(v -> v.getPropertyPath().toString().equals("qualityConsistentWithCatalog")),
        "the quality-by-kind guard must be skipped while the XOR guard fails");
  }

  @Test
  void bothCatalogReferences_violateXorGuard() {
    InventoryItemCreateDto payload = dto(UUID.randomUUID(), UUID.randomUUID(), 750, null, null);

    assertViolationOn(payload, "catalogReferenceValid");
  }

  @Test
  void materialRowWithoutQuality_violatesQualityGuard() {
    InventoryItemCreateDto payload = dto(UUID.randomUUID(), null, null, null, null);

    assertViolationOn(payload, "qualityConsistentWithCatalog");
  }

  @Test
  void gameItemRowWithQuality_violatesQualityGuard() {
    InventoryItemCreateDto payload = dto(null, UUID.randomUUID(), 750, null, null);

    assertViolationOn(payload, "qualityConsistentWithCatalog");
  }

  @Test
  void gameItemRowWithMissionId_violatesMissionGuard() {
    InventoryItemCreateDto payload = dto(null, UUID.randomUUID(), null, UUID.randomUUID(), null);

    assertViolationOn(payload, "missionFreeForGameItem");
  }

  @Test
  void gameItemRowWithMissionAllocations_violatesMissionGuard() {
    InventoryItemCreateDto payload =
        dto(
            null,
            UUID.randomUUID(),
            null,
            null,
            List.of(new InventoryAllocationInput(UUID.randomUUID(), 2.0)));

    assertViolationOn(payload, "missionFreeForGameItem");
  }

  @Test
  void wellFormedMaterialAndGameItemPayloads_haveNoViolations() {
    InventoryItemCreateDto materialPayload = dto(UUID.randomUUID(), null, 750, null, null);
    InventoryItemCreateDto itemPayload = dto(null, UUID.randomUUID(), null, null, null);

    assertTrue(validator.validate(materialPayload).isEmpty(), "material payload must be valid");
    assertTrue(validator.validate(itemPayload).isEmpty(), "game-item payload must be valid");
  }

  @Test
  void materialRowWithMission_isNotRejectedByTheItemGuard() {
    InventoryItemCreateDto payload = dto(UUID.randomUUID(), null, 750, UUID.randomUUID(), null);

    assertTrue(
        validator.validate(payload).isEmpty(),
        "the mission guard only applies to game-item payloads");
  }
}
