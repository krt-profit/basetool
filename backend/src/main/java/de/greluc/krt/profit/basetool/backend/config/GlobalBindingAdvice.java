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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Registers the {@link NormalizedStringEditor} for every bound {@code String} request property:
 * trimmed, NFC-normalized and capped at {@link StringNormalization#MAX_FREE_TEXT_LENGTH}.
 */
@ControllerAdvice
public class GlobalBindingAdvice {
  /**
   * Registers a trimming {@link NormalizedStringEditor} capped at {@link
   * StringNormalization#MAX_FREE_TEXT_LENGTH} for every {@code String} binding target.
   *
   * @param binder Spring's data binder for the current request
   */
  @InitBinder
  public void initBinder(@NotNull WebDataBinder binder) {
    binder.registerCustomEditor(
        String.class, new NormalizedStringEditor(StringNormalization.MAX_FREE_TEXT_LENGTH, true));
  }
}
