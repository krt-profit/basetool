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

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Constraint validator for {@link ValidQuantityAmount}.
 *
 * <p>Applies the quantity-type-specific rules listed on {@link ValidQuantityAmount}: {@code amount
 * > 0} for both types and whole-number amounts for {@code PIECE}. {@code SCU} fractional precision
 * is <em>not</em> rejected here — an amount with more than three decimals is commercially rounded
 * (HALF_UP) to three places at the persistence boundary (the {@code @PrePersist}/{@code @PreUpdate}
 * hooks on the amount entities), mirroring the frontend, so the server accepts and normalises it
 * rather than refusing it. If the material does not exist, validation silently passes — the
 * surrounding {@code @NotNull}/foreign-key checks (or the service layer) report that case so the
 * user gets the right error key, not a confusing "invalid quantity" message.
 *
 * <p>The PIECE-vs-SCU fact is resolved through the {@link MaterialPieceTypeLookup} seam rather than
 * a direct {@code MaterialRepository} call, so this {@code validation} class depends on neither
 * {@code repository} nor {@code model} and the package stays a dependency leaf (avoiding a {@code
 * model} &harr; {@code validation} cycle, since {@code model.dto} carries the constraint
 * annotation). A missing material yields {@code false} from the lookup, which is handled exactly
 * like a non-PIECE material: the whole-number check is skipped and validation passes.
 */
@Component
@RequiredArgsConstructor
public class ValidQuantityAmountValidator
    implements ConstraintValidator<ValidQuantityAmount, QuantityAware> {

  private final MaterialPieceTypeLookup materialPieceTypeLookup;

  @Override
  public boolean isValid(QuantityAware dto, ConstraintValidatorContext context) {
    if (dto == null || dto.amount() == null) {
      return true; // Let @NotNull handle these
    }

    // Game-item payloads (REQ-INV-029): unconditionally positive whole units, no catalog lookup
    // needed. Checked BEFORE the materialId null-guard — with materialId optional since V220, a
    // gameItemId-only payload would otherwise skip ALL amount validation (the silent hole the
    // item-inventory design flagged).
    if (dto.gameItemId() != null) {
      if (dto.amount() <= 0) {
        context.disableDefaultConstraintViolation();
        context
            .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_positive}")
            .addPropertyNode("amount")
            .addConstraintViolation();
        return false;
      }
      if (dto.amount() % 1 != 0) {
        context.disableDefaultConstraintViolation();
        context
            .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_integer}")
            .addPropertyNode("amount")
            .addConstraintViolation();
        return false;
      }
      return true;
    }

    if (dto.materialId() == null) {
      return true; // Let @NotNull / the catalog-XOR validation handle these
    }

    if (dto.amount() <= 0) {
      context.disableDefaultConstraintViolation();
      context
          .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_positive}")
          .addPropertyNode("amount")
          .addConstraintViolation();
      return false;
    }

    // A missing material resolves to false here, identical to a non-PIECE (SCU) material: the
    // whole-number rule is skipped and the missing-material case is reported by @NotNull /
    // foreign-key checks instead.
    if (materialPieceTypeLookup.isPieceQuantity(dto.materialId()) && dto.amount() % 1 != 0) {
      context.disableDefaultConstraintViolation();
      context
          .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_integer}")
          .addPropertyNode("amount")
          .addConstraintViolation();
      return false;
    }
    // SCU amounts with more than three decimals are NOT rejected: they are rounded HALF_UP to
    // three places at the persistence boundary (see roundAmountToScuScale on the amount entities),
    // so the server normalises fractional precision the same way the frontend does.
    return true;
  }
}
