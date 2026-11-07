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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBuild;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The patch version of a P4K run and where it comes from (REQ-P4K-005, ADR-0335 §6).
 *
 * @param version the patch version, e.g. {@code 4.10.2}
 * @param origin whether it is the public patch or a branch lower bound
 */
public record P4kPatchVersion(@NotNull String version, @NotNull P4kPatchVersionOrigin origin) {

  /**
   * A {@code major.minor.patch} version; at most 29 characters, inside the 32 of {@code
   * p4k_import_run.patch_version}.
   */
  private static final String VERSION = "(\\d{1,9}\\.\\d{1,9}\\.\\d{1,9})";

  /** The only shape a public patch takes, e.g. {@code 4.7.2}. */
  private static final Pattern PUBLIC_PATCH = Pattern.compile("^" + VERSION + "$");

  /** The only branch shape whose version is a lower bound of the public patch. */
  private static final Pattern RELEASE_BRANCH = Pattern.compile("^sc-alpha-" + VERSION + "$");

  /**
   * Derives the patch version of a Build: {@code publicPatch} when it reads {@code
   * major.minor.patch}, else the version of an exact {@code sc-alpha-<major>.<minor>.<patch>}
   * branch as a lower bound; a {@code publicPatch} of any other shape reads as unset.
   *
   * @param build the Build
   * @return the patch version, or {@code null} when it is unknown
   */
  @Nullable
  public static P4kPatchVersion of(@NotNull P4kBuild build) {
    String publicPatch = build.publicPatch();
    if (publicPatch != null && PUBLIC_PATCH.matcher(publicPatch.strip()).matches()) {
      return new P4kPatchVersion(publicPatch.strip(), P4kPatchVersionOrigin.PUBLIC_PATCH);
    }
    if (build.branch() == null) {
      return null;
    }
    Matcher matcher = RELEASE_BRANCH.matcher(build.branch());
    return matcher.matches()
        ? new P4kPatchVersion(matcher.group(1), P4kPatchVersionOrigin.BRANCH_LOWER_BOUND)
        : null;
  }
}
