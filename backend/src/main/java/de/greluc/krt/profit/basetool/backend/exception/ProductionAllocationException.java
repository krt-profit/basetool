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
 * Thrown when a production booking ("Herstellung", REQ-ORDERS-025) is well-formed and
 * version-current but violates a production quantity invariant: the requested amount exceeds the
 * item line's remaining-to-manufacture ({@code amount − manufacturedAmount}), or the per-material
 * consumption plan does not exactly cover the required material demand ({@code perUnit × amount}).
 *
 * <p>Distinct from a plain {@link BadRequestException} (400, malformed input) and from an {@code
 * ObjectOptimisticLockingFailureException} (409, stale version): the request is syntactically valid
 * and the versions are current, but the amounts are semantically rejected. Mapped to HTTP {@code
 * 422 Unprocessable Entity} with the stable code {@code PRODUCTION_ALLOCATION} by {@link
 * GlobalExceptionHandler}'s generic {@link AppException} dispatch — a status the frontend's {@code
 * krt-fetch.js} surfaces as an inline toast rather than the 409 reload-confirm, so the user can fix
 * the allocation without losing their edit. The frontend already pre-validates exact coverage, so
 * this is defence in depth against a stale or hand-crafted payload.
 *
 * <p>Every accessor is inherited unchanged from {@link AppException}, delegating to {@link
 * AppExceptionKind#PRODUCTION_ALLOCATION}.
 */
public final class ProductionAllocationException extends AppException {

  /**
   * Creates a {@code ProductionAllocationException} whose client-visible {@code detail} resolves
   * from the localized {@code problem.production_allocation.detail} bundle key (passed as the
   * message so {@code GlobalExceptionHandler.resolveDetail} translates it per the caller's {@code
   * Accept-Language} rather than leaking a hard-coded English string).
   */
  public ProductionAllocationException() {
    super(AppExceptionKind.PRODUCTION_ALLOCATION, "problem.production_allocation.detail");
  }
}
