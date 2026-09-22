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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One licence a shipped component is offered under, as the build-time licence report names it.
 *
 * <p>Licensee resolves a POM's {@code <license>} entry to an SPDX identifier where it can; one it
 * cannot resolve keeps only its POM name and URL and arrives here with a {@code null} {@link
 * #spdxId}. The build refuses such an entry unless it is allowed by URL or by coordinate in the
 * root build script, so an unresolved licence on this page is always one somebody reviewed.
 *
 * @param spdxId the SPDX identifier ({@code Apache-2.0}, {@code EPL-2.0}, ...), or {@code null}
 *     when the POM names a licence Licensee could not map to one
 * @param name the human-readable licence name shown as the group heading
 * @param url the canonical licence text the heading links to, or {@code null} when the source names
 *     none
 */
public record OssLicense(@Nullable String spdxId, @NotNull String name, @Nullable String url) {

  /**
   * The key the page groups components by: the SPDX identifier where there is one, the licence name
   * otherwise.
   *
   * <p>Grouping by name alone would split one licence into several groups, because POMs spell the
   * same licence a dozen ways ("Apache License, Version 2.0", "The Apache Software License, Version
   * 2.0"); Licensee has already normalised those to one identifier, and that is the key used.
   *
   * @return the SPDX identifier, or the name when the licence has none
   */
  public @NotNull String groupKey() {
    return spdxId != null ? spdxId : name;
  }
}
