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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How the frontend's Content-Security-Policy applies Trusted Types to the DOM script sinks
 * (ADR-0239, REQ-SEC-064), selected by {@value #PROPERTY}.
 */
@Slf4j
public enum TrustedTypesMode {

  /**
   * The Trusted Types directives go into a {@code Content-Security-Policy-Report-Only} header: a
   * sink written without a policy value is reported through the client-error beacon and still
   * works.
   */
  REPORT,

  /**
   * The Trusted Types directives are part of the enforced {@code Content-Security-Policy}: a sink
   * written without a policy value throws in the browser.
   */
  ENFORCE;

  /**
   * The property selecting the mode; the environment variable is {@code
   * APP_SECURITY_TRUSTED_TYPES}.
   */
  public static final String PROPERTY = "app.security.trusted-types";

  /**
   * Parses a configured mode leniently, falling back to {@link #REPORT} so a missing or mistyped
   * value never blocks a page.
   *
   * @param raw the configured value; case and surrounding whitespace are ignored
   * @return the matching mode, or {@link #REPORT} when {@code raw} is blank or unrecognised
   */
  public static @NotNull TrustedTypesMode parse(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return REPORT;
    }
    try {
      return valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      log.warn(
          "Unrecognised {} '{}'; falling back to REPORT. Valid values: REPORT, ENFORCE.",
          PROPERTY,
          raw);
      return REPORT;
    }
  }

  /**
   * The value of the {@code mode} tag on {@code basetool_trusted_types_mode}.
   *
   * @return {@code report} or {@code enforce}
   */
  @NotNull
  public String tag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
