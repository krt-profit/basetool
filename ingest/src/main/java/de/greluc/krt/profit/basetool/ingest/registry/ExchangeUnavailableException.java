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

package de.greluc.krt.profit.basetool.ingest.registry;

import java.io.Serial;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The gateway cannot read the registry or the revocations and fails closed (REQ-XCH-003). */
public class ExchangeUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what could not be read; never sent to the caller
   * @param cause the underlying failure, or {@code null}
   */
  public ExchangeUnavailableException(@NotNull String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
