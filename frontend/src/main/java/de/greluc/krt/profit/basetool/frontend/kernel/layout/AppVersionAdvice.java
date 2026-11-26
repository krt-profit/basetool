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

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the deployed version ({@link AppVersion}) to every layout model, for the sidebar's
 * version label and the page's version meta tag. Scoped to {@link UsesLayoutModel}.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class AppVersionAdvice {

  private final Optional<BuildProperties> buildProperties;

  /**
   * Resolves the version string for the {@code appVersion} Thymeleaf model attribute.
   *
   * @return the version {@link AppVersion#of} resolves, never blank
   */
  @ModelAttribute("appVersion")
  public String appVersion() {
    return AppVersion.of(buildProperties.orElse(null));
  }
}
