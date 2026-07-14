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
 * Thrown when a request would allocate more of an inventory entry's quantity to a dimension (its
 * job orders or its missions) than the entry actually holds — Σ of the dimension's slice amounts
 * would exceed the entry's own amount (Variante C, REQ-INV-027, rule R5).
 *
 * <p>Distinct from a plain {@link BadRequestException} (400, malformed input) and from an {@code
 * ObjectOptimisticLockingFailureException} (409, stale version): the request is well-formed and the
 * version is current, but the amount is semantically rejected by a business invariant. Mapped to
 * HTTP {@code 422 Unprocessable Entity} with the stable code {@code OVER_ALLOCATION} by {@link
 * GlobalExceptionHandler}'s generic {@link AppException} dispatch — a status the frontend's {@code
 * krt-fetch.js} surfaces as an inline toast rather than the 409 reload-confirm, so the user can fix
 * the amount without losing their edit.
 *
 * <p>Every accessor is inherited unchanged from {@link AppException}, delegating to {@link
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
