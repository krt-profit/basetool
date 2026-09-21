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
 * Global {@code @ControllerAdvice} that registers the {@link NormalizedStringEditor} for every
 * {@code String} property bound on incoming requests.
 *
 * <p>The editor trims leading/trailing whitespace, NFC-normalizes and caps every string at the
 * shared free-text limit ({@link StringNormalization#MAX_FREE_TEXT_LENGTH}). Applied globally so
 * individual controllers and DTOs do not need to repeat the normalization — and so that a forgotten
 * {@code @NotBlank} combined with an all-whitespace input still ends up rejected.
 */
@ControllerAdvice
public class GlobalBindingAdvice {
  /**
   * Registers {@link NormalizedStringEditor} for every {@code String} binding target. The editor's
   * {@code maxLength} ({@link StringNormalization#MAX_FREE_TEXT_LENGTH}) matches the longest
   * free-text field stored in the database and {@code trim=true} is what callers of every
   * controller expect.
   *
   * @param binder Spring's data binder for the current request
   */
  @InitBinder
  public void initBinder(@NotNull WebDataBinder binder) {
    binder.registerCustomEditor(
        String.class, new NormalizedStringEditor(StringNormalization.MAX_FREE_TEXT_LENGTH, true));
  }
}
