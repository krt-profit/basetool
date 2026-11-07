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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import java.io.Serial;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown while reading when the decompressed catalogue, or the part of it the import holds in
 * memory, passes its size limit.
 */
public final class P4kContentTooLargeException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception for the decompressed limit.
   *
   * @param limitBytes the decompressed limit in bytes that was passed
   */
  public P4kContentTooLargeException(long limitBytes) {
    this("The decompressed catalogue", limitBytes);
  }

  /**
   * Creates the exception for a limit on a named part of the catalogue.
   *
   * @param measured what was measured, the subject of the message
   * @param limitBytes the limit in bytes that was passed
   */
  public P4kContentTooLargeException(@NotNull String measured, long limitBytes) {
    super(measured + " exceeds " + limitBytes + " bytes.");
  }
}
