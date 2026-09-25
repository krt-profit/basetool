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

import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates the JWT {@code aud} configuration at startup (REQ-SEC-024).
 *
 * <p>Under the {@code prod} profile a blank {@code app.security.jwt.expected-audiences} aborts the
 * start; other profiles treat blank as "no audience check" and log it at WARN. The accepted
 * audiences are logged at INFO.
 */
@Slf4j
@Component
public class JwtAudienceStartupCheck {

  /** The profile under which a blank audience list refuses the start. */
  static final String PROD_PROFILE = "prod";

  /**
   * The effective, trimmed, non-blank audiences the resource server enforces; empty when the check
   * is off (only possible outside {@code prod}).
   */
  @Getter @NotNull @Unmodifiable private final List<String> audiences;

  /**
   * Evaluates the configured audiences once, at context start.
   *
   * @param environment the environment, queried for the active profiles
   * @param expectedAudiences the raw comma-separated audience list; blank entries are ignored
   * @throws IllegalStateException when {@code prod} is active and no audience is configured
   */
  public JwtAudienceStartupCheck(
      @NotNull Environment environment,
      @Value("${app.security.jwt.expected-audiences:}") @NotNull List<String> expectedAudiences) {
    this.audiences =
        expectedAudiences.stream().filter(StringUtils::hasText).map(String::trim).toList();
    if (!audiences.isEmpty()) {
      log.info("JWT audience check enforced; accepted audiences: {}", audiences);
      return;
    }
    if (environment.matchesProfiles(PROD_PROFILE)) {
      throw new IllegalStateException(
          "app.security.jwt.expected-audiences is blank under the prod profile. Set"
              + " IRI_BACKEND_EXPECTED_AUDIENCES (normally basetool-backend) in the host .env:"
              + " without it the backend would accept any validly-signed token of the realm.");
    }
    log.warn(
        "JWT audience check is OFF (app.security.jwt.expected-audiences is blank); every"
            + " validly-signed token of the realm is accepted. Allowed outside the prod profile"
            + " only.");
  }
}
