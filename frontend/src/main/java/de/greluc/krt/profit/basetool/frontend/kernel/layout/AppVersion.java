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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.info.BuildProperties;

/**
 * Resolves the version of the running frontend: the layout shows it, and every answer carries it,
 * so an open page notices that a release replaced the version it was rendered with (REQ-FE-042).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AppVersion {

  /**
   * The version without build info, as in sliced web-MVC tests that skip {@code
   * ProjectInfoAutoConfiguration} and in local runs that package no build info.
   */
  static final String FALLBACK_VERSION = "dev";

  /**
   * Resolves the running version: the {@code version} of {@code META-INF/build-info.properties},
   * else {@link #FALLBACK_VERSION} when there is no build info or its version is blank.
   *
   * @param buildProperties the build info; {@code null} when the context has none
   * @return the version, never blank
   */
  @NotNull
  public static String of(@Nullable BuildProperties buildProperties) {
    String version = buildProperties == null ? null : buildProperties.getVersion();
    return version == null || version.isBlank() ? FALLBACK_VERSION : version;
  }
}
