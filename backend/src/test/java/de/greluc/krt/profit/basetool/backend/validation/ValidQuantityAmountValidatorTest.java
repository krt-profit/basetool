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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import jakarta.validation.ConstraintValidatorContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// covers REQ-INV-003 (server-side validation) - see docs/specs/inv-material-quantities.md
@ExtendWith(MockitoExtension.class)
class ValidQuantityAmountValidatorTest {

  @Mock private MaterialPieceTypeLookup materialPieceTypeLookup;

  @Mock private ConstraintValidatorContext context;

  @Mock private ConstraintValidatorContext.ConstraintViolationBuilder builder;

  @Mock
  private ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext
      nodeBuilder;

  private ValidQuantityAmountValidator validator;
  private UUID materialId;

  @BeforeEach
  void setUp() {
    validator = new ValidQuantityAmountValidator(materialPieceTypeLookup);
    materialId = UUID.randomUUID();
  }

  @Test
  void shouldBeValidWhenNull() {
    QuantityAware dto = new TestDto(null, null);
    assertTrue(validator.isValid(dto, context));

    dto = new TestDto(materialId, null);
    assertTrue(validator.isValid(dto, context));

    dto = new TestDto(null, 1.0);
    assertTrue(validator.isValid(dto, context));
  }

  @Test
  void shouldBeInvalidWhenNegativeOrZero() {
    when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
    when(builder.addPropertyNode("amount")).thenReturn(nodeBuilder);

    QuantityAware dto0 = new TestDto(materialId, 0.0);
    assertFalse(validator.isValid(dto0, context));

    QuantityAware dtoNeg = new TestDto(materialId, -5.0);
    assertFalse(validator.isValid(dtoNeg, context));

    verify(context, times(2)).disableDefaultConstraintViolation();
    verify(context, times(2))
        .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_positive}");
  }

  @Test
  void shouldBeValidWhenPieceIsInteger() {
    when(materialPieceTypeLookup.isPieceQuantity(materialId)).thenReturn(true);

    QuantityAware dto = new TestDto(materialId, 5.0);
    assertTrue(validator.isValid(dto, context));
  }

  @Test
  void shouldBeInvalidWhenPieceIsDecimal() {
    when(materialPieceTypeLookup.isPieceQuantity(materialId)).thenReturn(true);

    when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
    when(builder.addPropertyNode("amount")).thenReturn(nodeBuilder);

    QuantityAware dto = new TestDto(materialId, 5.5);
    assertFalse(validator.isValid(dto, context));

    verify(context).disableDefaultConstraintViolation();
    verify(context)
        .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_integer}");
  }

  @Test
  void shouldBeValidWhenScuHasUpToThreeDecimals() {
    when(materialPieceTypeLookup.isPieceQuantity(materialId)).thenReturn(false);

    assertTrue(validator.isValid(new TestDto(materialId, 10.0), context));
    assertTrue(validator.isValid(new TestDto(materialId, 10.12), context));
    assertTrue(validator.isValid(new TestDto(materialId, 10.123), context));
  }

  @Test
  void shouldBeValidWhenScuHasMoreThanThreeDecimals() {
    // SCU precision is no longer rejected: an amount with more than three decimals is rounded
    // HALF_UP to three places at the persistence boundary (the entity @PrePersist/@PreUpdate
    // hooks), mirroring the frontend, so the validator must accept it rather than refuse it.
    when(materialPieceTypeLookup.isPieceQuantity(materialId)).thenReturn(false);

    assertTrue(validator.isValid(new TestDto(materialId, 10.1234), context));
  }

  // --- game-item payloads (REQ-INV-029) -------------------------------------

  // covers REQ-INV-029 (gameItem amounts: positive whole units, no catalog lookup)
  @Test
  void gameItemPayload_wholePositiveAmount_isValid_withoutMaterialLookup() {
    // Given a gameItem-only payload with a positive whole amount
    QuantityAware dto = new TestItemDto(UUID.randomUUID(), 5.0);

    // When / Then — valid, and the PIECE lookup seam is never consulted (item amounts are
    // unconditionally whole units, no catalog metadata needed).
    assertTrue(validator.isValid(dto, context));
    verifyNoInteractions(materialPieceTypeLookup);
  }

  // covers REQ-INV-029 (the closed validator hole: a gameItemId-only payload IS validated)
  @Test
  void gameItemPayload_zeroOrNegativeAmount_isInvalid() {
    // Given the violation-builder plumbing
    when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
    when(builder.addPropertyNode("amount")).thenReturn(nodeBuilder);

    // When / Then — with materialId == null the pre-V220 validator silently skipped ALL amount
    // rules; the closed hole now rejects zero and negative amounts on the gameItem branch.
    assertFalse(validator.isValid(new TestItemDto(UUID.randomUUID(), 0.0), context));
    assertFalse(validator.isValid(new TestItemDto(UUID.randomUUID(), -3.0), context));

    verify(context, times(2)).disableDefaultConstraintViolation();
    verify(context, times(2))
        .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_positive}");
    verifyNoInteractions(materialPieceTypeLookup);
  }

  // covers REQ-INV-029 (gameItem amounts are whole units)
  @Test
  void gameItemPayload_fractionalAmount_isInvalid() {
    // Given the violation-builder plumbing
    when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
    when(builder.addPropertyNode("amount")).thenReturn(nodeBuilder);

    // When / Then — fractional item amounts are rejected like PIECE materials, but without any
    // MaterialPieceTypeLookup interaction.
    assertFalse(validator.isValid(new TestItemDto(UUID.randomUUID(), 2.5), context));

    verify(context).disableDefaultConstraintViolation();
    verify(context)
        .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_integer}");
    verifyNoInteractions(materialPieceTypeLookup);
  }

  // covers REQ-INV-029 (catalog precedence: the gameItem branch wins over the material branch)
  @Test
  void dualCatalogPayload_gameItemBranchWins_withoutMaterialLookup() {
    // Given the violation-builder plumbing and a payload carrying BOTH catalog references (a
    // crafted shape the DTO's @AssertTrue XOR guard rejects separately)
    when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
    when(builder.addPropertyNode("amount")).thenReturn(nodeBuilder);

    // When / Then — the fractional amount is rejected under the item rule: the gameItem branch
    // runs first, so the material/PIECE lookup is never consulted even though materialId is set.
    assertFalse(validator.isValid(new TestDualDto(materialId, UUID.randomUUID(), 2.5), context));

    verify(context).disableDefaultConstraintViolation();
    verify(context)
        .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_integer}");
    verifyNoInteractions(materialPieceTypeLookup);
  }

  record TestDto(UUID materialId, Double amount) implements QuantityAware {}

  /**
   * Dual-catalog {@link QuantityAware} probe (REQ-INV-029): carries BOTH a {@code materialId} and a
   * {@code gameItemId} — the crafted shape the DTO XOR guard rejects — so the precedence test can
   * pin that the validator's gameItem branch runs first and the material/PIECE lookup stays
   * untouched.
   *
   * @param materialId the referenced material
   * @param gameItemId the referenced game item
   * @param amount the requested quantity
   */
  record TestDualDto(UUID materialId, UUID gameItemId, Double amount) implements QuantityAware {}

  /**
   * Game-item flavoured {@link QuantityAware} probe (REQ-INV-029): carries only a {@code
   * gameItemId} and an amount, with {@link #materialId()} fixed to {@code null} — the payload shape
   * whose amount rules the validator's gameItem branch enforces.
   *
   * @param gameItemId the referenced game item
   * @param amount the requested quantity
   */
  record TestItemDto(UUID gameItemId, Double amount) implements QuantityAware {
    @Override
    public UUID materialId() {
      return null;
    }
  }
}
