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

package de.greluc.krt.profit.basetool.ingest.problem;

/**
 * Signals a client-side problem with an ingest request that the gateway itself detects (e.g. a
 * blueprint body that is not a JSON object), mapped to HTTP 400 {@code application/problem+json} by
 * {@link de.greluc.krt.profit.basetool.ingest.web.GlobalExceptionHandler}.
 */
public class BadRequestException extends RuntimeException {

  /**
   * Creates the exception with a human-readable, non-sensitive message used as the problem detail.
   *
   * @param message the detail message (no tokens or PII)
   */
  public BadRequestException(String message) {
    super(message);
  }
}
