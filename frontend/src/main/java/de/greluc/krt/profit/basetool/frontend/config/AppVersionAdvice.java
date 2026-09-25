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

package de.greluc.krt.profit.basetool.frontend.config;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the deployed version from {@link BuildProperties} to every layout model for the sidebar's
 * version label; without build info it falls back to {@code "dev"}. Scoped to {@link
 * UsesLayoutModel}.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class AppVersionAdvice {

  /**
   * Fallback string used whenever no {@link BuildProperties} bean is on the context (typical for
   * sliced web-MVC test runs that skip {@code ProjectInfoAutoConfiguration}). Kept short on purpose
   * so the sidebar chip stays unobtrusive in non-prod environments where the canonical Gradle
   * version isn't packaged.
   */
  static final String FALLBACK_VERSION = "dev";

  private final Optional<BuildProperties> buildProperties;

  /**
   * Resolves the version string for the {@code appVersion} Thymeleaf model attribute. Prefers the
   * value read from {@code META-INF/build-info.properties}; falls back to {@link #FALLBACK_VERSION}
   * when the auto-configured bean is absent or its {@code version} field is blank.
   *
   * @return non-{@code null} version string suitable for direct rendering in the sidebar fragment.
   */
  @ModelAttribute("appVersion")
  public String appVersion() {
    return buildProperties
        .map(BuildProperties::getVersion)
        .filter(v -> v != null && !v.isBlank())
        .orElse(FALLBACK_VERSION);
  }
}
