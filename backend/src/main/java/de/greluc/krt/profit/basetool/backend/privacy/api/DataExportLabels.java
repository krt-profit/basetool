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

package de.greluc.krt.profit.basetool.backend.privacy.api;

import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Resolves the German texts of a data export (REQ-SEC-058): the PDF's labels and the information
 * both documents carry. The export is written in German, the language of the organisation.
 */
@Component
@RequiredArgsConstructor
public class DataExportLabels {

  private final MessageSource messageSource;

  /**
   * Resolves one German text from the backend message bundle.
   *
   * @param key the message key
   * @return the resolved text, or the key itself when it is missing
   */
  public @NotNull String label(@NotNull String key) {
    return Objects.requireNonNullElse(messageSource.getMessage(key, null, key, Locale.GERMAN), key);
  }
}
