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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Static Thymeleaf page controller for the Impressum ({@code /impressum}), the operator disclosure
 * of § 5 DDG and § 18 Abs. 1 MStV with the DSA contact point (REQ-UI-040). Renders a fixed template
 * without backend data.
 */
@Controller
@UsesLayoutModel
public class ImpressumController {

  /**
   * Returns the {@code impressum} view name.
   *
   * @return the {@code impressum} view name
   */
  @NotNull
  @GetMapping("/impressum")
  public String showImpressum() {
    return "shell/impressum";
  }
}
