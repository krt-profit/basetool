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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import java.io.OutputStream;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;

/** Streams one authorized backend download without retaining its complete body. */
@FunctionalInterface
public interface BackendBinaryTransfer {

  /**
   * Validates the backend response before exposing headers or bytes and streams it once.
   *
   * @param output the browser's response stream
   * @param ready prepares download headers after the backend accepts the request
   */
  void writeTo(@NotNull OutputStream output, @NotNull Consumer<HttpHeaders> ready);
}
