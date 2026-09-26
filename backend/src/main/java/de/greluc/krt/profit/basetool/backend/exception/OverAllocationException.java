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

package de.greluc.krt.profit.basetool.backend.exception;

/**
 * Thrown when an allocation to a dimension (job orders or missions) would exceed the inventory
 * entry's own amount (REQ-INV-027).
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} with code {@code OVER_ALLOCATION} via {@link
 * AppExceptionKind#OVER_ALLOCATION}.
 */
public final class OverAllocationException extends AppException {

  /**
   * Creates an {@code OverAllocationException} whose client-visible {@code detail} resolves from
   * the localized {@code problem.over_allocation.detail} bundle key (passed as the message so
   * {@code GlobalExceptionHandler.resolveDetail} translates it per the caller's {@code
   * Accept-Language} rather than leaking a hard-coded English string).
   */
  public OverAllocationException() {
    super(AppExceptionKind.OVER_ALLOCATION, "problem.over_allocation.detail");
  }
}
