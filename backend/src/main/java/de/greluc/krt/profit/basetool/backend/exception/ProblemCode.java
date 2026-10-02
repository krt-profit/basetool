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

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * One stable value of the {@code code} property of an RFC 7807 problem body (REQ-API-004,
 * REQ-API-019, ADR-0235).
 *
 * <p>Every code the backend emits is a constant of an enum implementing this interface; the code
 * string is the contract, the Java name is not. The exchange's own codes stay in {@link
 * ExchangeProblemException}, which is frozen.
 */
public interface ProblemCode {

  /**
   * Returns the wire value of the code.
   *
   * @return the upper-snake-case string a client compares against
   */
  @NotNull
  String code();

  /**
   * Returns the HTTP status the code is answered with.
   *
   * @return the status of the response that carries the code
   */
  @NotNull
  HttpStatus status();
}
