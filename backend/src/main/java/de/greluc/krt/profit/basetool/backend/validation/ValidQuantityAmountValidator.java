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
 * Validator for {@link ValidQuantityAmount}. A missing material passes, so the surrounding
 * {@code @NotNull} or foreign-key checks report it; the PIECE fact comes from {@link
 * MaterialPieceTypeLookup}.
 */
@Component
@RequiredArgsConstructor
public class ValidQuantityAmountValidator
    implements ConstraintValidator<ValidQuantityAmount, QuantityAware> {

  private final MaterialPieceTypeLookup materialPieceTypeLookup;

  @Override
  public boolean isValid(QuantityAware dto, ConstraintValidatorContext context) {
    if (dto == null || dto.amount() == null) {
      return true;
    }

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
      return true;
    }

    if (dto.amount() <= 0) {
      context.disableDefaultConstraintViolation();
      context
          .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_positive}")
          .addPropertyNode("amount")
          .addConstraintViolation();
      return false;
    }

    if (materialPieceTypeLookup.isPieceQuantity(dto.materialId()) && dto.amount() % 1 != 0) {
      context.disableDefaultConstraintViolation();
      context
          .buildConstraintViolationWithTemplate("{error.validation.quantity_must_be_integer}")
          .addPropertyNode("amount")
          .addConstraintViolation();
      return false;
    }
    return true;
  }
}
