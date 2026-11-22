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

package de.greluc.krt.profit.basetool.backend.support;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/** Locates the repository root for tests that read files of other modules. */
public final class RepositoryRoot {

  private RepositoryRoot() {}

  /**
   * Walks up from the test's working directory to the directory holding {@code
   * settings.gradle.kts}, so cross-module paths resolve whichever module directory the test task
   * runs in.
   *
   * @return the repository root directory
   * @throws IllegalStateException if no {@code settings.gradle.kts} is found on the way up
   */
  public static @NotNull Path find() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
          "Could not locate the repository root (settings.gradle.kts) from "
              + System.getProperty("user.dir"));
    }
    return dir;
  }
}
