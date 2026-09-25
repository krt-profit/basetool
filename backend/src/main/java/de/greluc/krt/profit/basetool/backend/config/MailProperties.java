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

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration under {@code app.mail.*} for the transactional e-mail channel
 * (REQ-NOTIF-013).
 *
 * <p>Mail is sent only when {@code enabled} is {@code true} and a {@code JavaMailSender} exists,
 * which requires {@code spring.mail.host}.
 *
 * @param enabled the kill switch for outbound mail; defaults to {@code false} when unbound
 * @param from the envelope sender address of every outbound mail
 * @param fromName the display name prefixed to {@code from}; blank uses the bare address
 * @param defaultLocale the BCP-47 tag selecting the language of system-initiated mails
 */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("no-reply@profit-base.online") @NotBlank @Email String from,
    @DefaultValue("Profit Basetool") @NotBlank String fromName,
    @DefaultValue("de") @NotBlank String defaultLocale) {

  /**
   * Resolves {@code defaultLocale} to a {@link Locale} for composing system-initiated mails. A
   * blank or unknown tag yields {@link Locale#ROOT}, for which the backend {@code MessageSource}
   * falls back to the default (German) bundle.
   *
   * @return the parsed default locale
   */
  public Locale resolveDefaultLocale() {
    return Locale.forLanguageTag(defaultLocale);
  }
}
