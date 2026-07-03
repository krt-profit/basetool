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
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration for the transactional e-mail channel (REQ-NOTIF-013).
 *
 * <p>Bound under {@code app.mail.*}. This block is the app-level gate and envelope metadata; the
 * SMTP transport (host / port / credentials) is Spring's own {@code spring.mail.*}. A mail is only
 * sent when {@link #enabled} is {@code true} <b>and</b> a {@code JavaMailSender} exists (which
 * Spring Boot autoconfigures only when {@code spring.mail.host} is set) — both default off, so
 * dev/test/CI never send real mail. Validated via Jakarta-Validation so a malformed sender address
 * fails the context start early rather than at first send.
 */
@Getter
@Setter
@ToString
@Validated
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

  /**
   * Kill-switch for outbound mail. The shipped config ({@code application.yml}) sets this {@code
   * true}; the effective on/off is whether an SMTP host is configured, so with no host mail is a
   * no-op even while enabled. Set {@code APP_MAIL_ENABLED=false} to hard-disable regardless of SMTP
   * config. The field initializer stays {@code false} as a fail-safe if the property is ever
   * unbound.
   */
  private boolean enabled = false;

  /**
   * Envelope sender address used as the {@code From} of every outbound mail. Defaults to the
   * project's {@code no-reply} mailbox so validation passes even when mail is disabled; override in
   * prod via {@code APP_MAIL_FROM_ADDRESS}.
   */
  @NotBlank @Email private String from = "no-reply@profit-base.online";

  /**
   * Human-readable display name prefixed to the {@link #from} address ({@code Name <addr>}). Purely
   * cosmetic; blanking it falls back to the bare address.
   */
  @NotBlank private String fromName = "Profit Basetool";

  /**
   * BCP-47 language tag selecting the language of system-initiated mails (e.g. the account
   * approval/rejection notice). No per-recipient locale is stored yet, so this default drives every
   * such mail; German by default, matching the organisation's primary language.
   */
  @NotBlank private String defaultLocale = "de";

  /**
   * Resolves {@link #defaultLocale} to a {@link Locale} for composing system-initiated mails. A
   * blank or unknown tag yields {@link Locale#ROOT}, for which the backend {@code MessageSource}
   * falls back to the default (German) bundle.
   *
   * @return the parsed default locale
   */
  public Locale resolveDefaultLocale() {
    return Locale.forLanguageTag(defaultLocale);
  }
}
