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
 * Bean Validation contract tests for the three catalog guards on {@link InventoryItemCreateDto}
 * (V220, REQ-INV-029/031): the material/gameItem XOR, the quality-by-kind pairing, and the
 * no-mission-for-game-item rule. Each guard mirrors a DB CHECK or service invariant so a violating
 * payload surfaces as a 400 validation error instead of a 500 integrity failure — a silently
 * dropped {@code @AssertTrue} would regress exactly that, with nothing failing at the type level.
 *
 * <p>The class-level {@code @ValidQuantityAmount} needs its Spring-managed validator, so the
 * factory is configured with a {@link ConstraintValidatorFactory} that hands the {@link
 * ValidQuantityAmountValidator} a stub {@code MaterialPieceTypeLookup} (every material SCU) and
 * falls back to default construction for the built-in validators.
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
                  public void releaseInstance(ConstraintValidator<?, ?> instance) {
                    // Stateless test validators — nothing to release.
                  }
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
   * Builds a create payload with the given catalog references, quality and mission fields; every
   * other field is a fixed valid value (location set, amount 5.0, non-personal, no allocations).
   *
   * @param materialId the material reference, or {@code null}
   * @param gameItemId the game-item reference, or {@code null}
   * @param quality the quality grade, or {@code null}
   * @param missionId the legacy single mission reference, or {@code null}
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

  // covers REQ-INV-029 (catalog XOR — mirrors chk_inventory_item_catalog_xor)
  @Test
  void neitherCatalogReference_violatesXorGuard() {
    // Given a payload with neither materialId nor gameItemId
    InventoryItemCreateDto payload = dto(null, null, null, null, null);

    // When / Then — the XOR guard reports; the quality guard deliberately stays silent while the
    // XOR already fails (so the caller sees one root cause, not two).
    assertViolationOn(payload, "catalogReferenceValid");
    assertTrue(
        validator.validate(payload).stream()
            .noneMatch(v -> v.getPropertyPath().toString().equals("qualityConsistentWithCatalog")),
        "the quality-by-kind guard must be skipped while the XOR guard fails");
  }

  // covers REQ-INV-029 (catalog XOR)
  @Test
  void bothCatalogReferences_violateXorGuard() {
    // Given a payload naming a material AND a game item
    InventoryItemCreateDto payload = dto(UUID.randomUUID(), UUID.randomUUID(), 750, null, null);

    // When / Then
    assertViolationOn(payload, "catalogReferenceValid");
  }

  // covers REQ-INV-029 (quality-by-kind — material row requires a quality)
  @Test
  void materialRowWithoutQuality_violatesQualityGuard() {
    // Given a material payload with no quality
    InventoryItemCreateDto payload = dto(UUID.randomUUID(), null, null, null, null);

    // When / Then
    assertViolationOn(payload, "qualityConsistentWithCatalog");
  }

  // covers REQ-INV-029 (quality-by-kind — game-item row forbids a quality)
  @Test
  void gameItemRowWithQuality_violatesQualityGuard() {
    // Given a game-item payload carrying a quality
    InventoryItemCreateDto payload = dto(null, UUID.randomUUID(), 750, null, null);

    // When / Then
    assertViolationOn(payload, "qualityConsistentWithCatalog");
  }

  // covers REQ-INV-031 (game-item rows carry no mission dimension)
  @Test
  void gameItemRowWithMissionId_violatesMissionGuard() {
    // Given a game-item payload with the legacy single mission reference
    InventoryItemCreateDto payload = dto(null, UUID.randomUUID(), null, UUID.randomUUID(), null);

    // When / Then
    assertViolationOn(payload, "missionFreeForGameItem");
  }

  // covers REQ-INV-031 (game-item rows carry no mission dimension — split list variant)
  @Test
  void gameItemRowWithMissionAllocations_violatesMissionGuard() {
    // Given a game-item payload with a Variante-C mission split
    InventoryItemCreateDto payload =
        dto(
            null,
            UUID.randomUUID(),
            null,
            null,
            List.of(new InventoryAllocationInput(UUID.randomUUID(), 2.0)));

    // When / Then
    assertViolationOn(payload, "missionFreeForGameItem");
  }

  // covers REQ-INV-029 (well-formed payloads of both catalog kinds pass)
  @Test
  void wellFormedMaterialAndGameItemPayloads_haveNoViolations() {
    // Given a material payload with quality and a game-item payload without one
    InventoryItemCreateDto materialPayload = dto(UUID.randomUUID(), null, 750, null, null);
    InventoryItemCreateDto itemPayload = dto(null, UUID.randomUUID(), null, null, null);

    // When / Then
    assertTrue(validator.validate(materialPayload).isEmpty(), "material payload must be valid");
    assertTrue(validator.validate(itemPayload).isEmpty(), "game-item payload must be valid");
  }

  // covers REQ-INV-031 (a material payload keeps its mission dimension)
  @Test
  void materialRowWithMission_isNotRejectedByTheItemGuard() {
    // Given a material payload with a mission reference — the pre-item contract
    InventoryItemCreateDto payload = dto(UUID.randomUUID(), null, 750, UUID.randomUUID(), null);

    // When / Then
    assertTrue(
        validator.validate(payload).isEmpty(),
        "the mission guard only applies to game-item payloads");
  }
}
