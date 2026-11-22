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

package de.greluc.krt.profit.basetool.frontend.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/** Resolves repository-relative paths for tests that read files outside the frontend module. */
final class RepositoryPaths {

  private RepositoryPaths() {}

  /**
   * Resolves a repository-relative path whether the test runs from the {@code frontend} module or
   * the repository root.
   *
   * @param repositoryRelative the path relative to the repository root
   * @return the module-relative candidate if it exists, the root-relative one otherwise
   */
  static @NotNull Path resolve(@NotNull String repositoryRelative) {
    Path fromModule = Path.of("..").resolve(repositoryRelative);
    return Files.exists(fromModule) ? fromModule : Path.of(repositoryRelative);
  }
}
