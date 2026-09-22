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

package de.greluc.krt.profit.basetool.frontend.oss;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The build-time licence report exactly as {@code :frontend:generateOssLicenses} writes it to
 * {@code oss/oss-licenses.json} on the classpath.
 *
 * <p>The file is generated at build time from the Licensee reports of every shipped module plus the
 * hand-kept bundled-asset list, so the page states the versions of the build that serves it and
 * cannot drift from the dependency graph. It is not committed.
 *
 * @param generator the tool and version that produced the library half, e.g. {@code licensee
 *     1.14.1}, shown in the page's closing line; {@code null} if the file does not say
 * @param components every shipped component, unsorted and ungrouped; {@link OssLicenseCatalog} does
 *     the grouping
 */
public record OssLicenseReport(
    @Nullable String generator, @NotNull @Unmodifiable List<OssComponent> components) {

  /**
   * Copies the component list; a file without one reads as an empty report.
   *
   * @param generator see the record description
   * @param components see the record description; {@code null} is read as empty
   */
  public OssLicenseReport {
    components = components == null ? List.of() : List.copyOf(components);
  }
}
