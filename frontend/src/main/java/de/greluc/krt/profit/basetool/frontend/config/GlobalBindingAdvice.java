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

import de.greluc.krt.profit.basetool.frontend.support.StringNormalization;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Registers the trimming, length-capping {@code String} property editor for every controller,
 * including the REST controllers whose {@code @RequestParam} and {@code @PathVariable} strings go
 * through the {@link WebDataBinder}.
 *
 * <p>Deliberately not scoped to {@link UsesLayoutModel}.
 */
@ControllerAdvice
public class GlobalBindingAdvice {
  /**
   * Registers {@link NormalizedStringEditor} for every controller so form-bound Strings are trimmed
   * and length-capped at {@link StringNormalization#MAX_FREE_TEXT_LENGTH} before validation runs.
   */
  @InitBinder
  public void initBinder(@NotNull WebDataBinder binder) {
    binder.registerCustomEditor(
        String.class, new NormalizedStringEditor(StringNormalization.MAX_FREE_TEXT_LENGTH, true));
  }
}
