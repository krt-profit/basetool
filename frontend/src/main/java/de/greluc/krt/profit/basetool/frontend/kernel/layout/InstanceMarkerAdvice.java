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

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the {@code testSystem} flag that switches on the footer's test-system marker
 * (REQ-UI-029). Scoped to {@link UsesLayoutModel}.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class InstanceMarkerAdvice {

  /** The configured instance kind. */
  private final InstanceProperties instanceProperties;

  /**
   * Whether this instance is the test system, for the {@code testSystem} Thymeleaf model attribute.
   *
   * @return the value of {@code app.instance.test-system}
   */
  @ModelAttribute("testSystem")
  public boolean testSystem() {
    return instanceProperties.testSystem();
  }
}
