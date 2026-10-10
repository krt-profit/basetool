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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.exception.DomainProblem;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a production booking (REQ-ORDERS-025) exceeds the item line's remaining amount or its
 * consumption plan does not exactly cover the required material.
 *
 * <p>Mapped to {@code 422 Unprocessable Entity} with code {@link
 * JobOrderProblemCode#PRODUCTION_ALLOCATION}.
 */
public final class ProductionAllocationException extends DomainProblem {

  /**
   * Creates a {@code ProductionAllocationException} whose client-visible {@code detail} resolves
   * from the localized {@code problem.production_allocation.detail} bundle key (passed as the
   * message so {@code GlobalExceptionHandler.resolveDetail} translates it per the caller's {@code
   * Accept-Language} rather than leaking a hard-coded English string).
   */
  public ProductionAllocationException() {
    super("problem.production_allocation.detail");
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 422}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return JobOrderProblemCode.PRODUCTION_ALLOCATION.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code PRODUCTION_ALLOCATION}
   */
  @NotNull
  @Override
  public String code() {
    return JobOrderProblemCode.PRODUCTION_ALLOCATION.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Production allocation"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Production allocation";
  }
}
