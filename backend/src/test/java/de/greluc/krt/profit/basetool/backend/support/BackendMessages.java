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

package de.greluc.krt.profit.basetool.backend.support;

import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The backend message bundle {@code messages*.properties} as the application configures it, for
 * tests that render localized text without a Spring context.
 */
public final class BackendMessages {

  private BackendMessages() {}

  /**
   * Creates a message source over the backend bundle, decoded as UTF-8 and without a fallback to
   * the system locale, matching {@code spring.messages} in {@code application.yml}.
   *
   * @return a new message source
   */
  public static @NotNull MessageSource bundle() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
    messages.setFallbackToSystemLocale(false);
    return messages;
  }
}
