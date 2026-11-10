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

package de.greluc.krt.profit.basetool.testsupport.process;

import java.io.Serial;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Attached as suppressed to a timeout or interrupt of {@link OwnedProcessTree#run} when owned
 * processes outlived the stop budget; a caller must not start another attempt beside them.
 */
public final class UnstoppedProcessesException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception naming the surviving PIDs.
   *
   * @param survivors the owned processes still alive after the stop budget
   */
  UnstoppedProcessesException(@NotNull List<ProcessHandle> survivors) {
    super(
        "owned processes still alive after the stop budget: "
            + survivors.stream()
                .map(handle -> Long.toString(handle.pid()))
                .collect(Collectors.joining(", ")));
  }
}
