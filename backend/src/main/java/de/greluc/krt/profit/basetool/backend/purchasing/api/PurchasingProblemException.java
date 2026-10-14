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

package de.greluc.krt.profit.basetool.backend.purchasing.api;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import java.util.Map;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpStatus;

/**
 * A purchasing rule that refuses the request, carrying its {@link PurchasingProblemCode}; the
 * status and the RFC 7807 {@code code} follow from it.
 */
@Getter
public final class PurchasingProblemException extends DomainProblem {

  /** The problem code the response carries. */
  private final PurchasingProblemCode problemCode;

  /**
   * Creates the exception.
   *
   * @param problemCode the code, which fixes the status
   * @param message the detail, literal English
   */
  public PurchasingProblemException(
      @NotNull PurchasingProblemCode problemCode, @NotNull String message) {
    super(message);
    this.problemCode = problemCode;
  }

  @Override
  public HttpStatus status() {
    return problemCode.status();
  }

  @Override
  public String code() {
    return problemCode.code();
  }

  @NotNull
  @Override
  public String logLabel() {
    return "Purchasing rule";
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, ?> logExtra() {
    return Map.of("purchasingCode", problemCode.code());
  }
}
