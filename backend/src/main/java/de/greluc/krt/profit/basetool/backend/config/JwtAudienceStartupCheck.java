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
 * Startup gate for the JWT {@code aud} check of the resource server (REQ-SEC-024, APPSEC-08).
 *
 * <p>{@code app.security.jwt.expected-audiences} is what makes {@link
 * SecurityConfig#resourceServerJwtDecoder} add its audience validator. Blank means "no audience
 * check", which is the right default for dev, test and a hand-rolled local realm — and was also
 * what production silently fell back to whenever {@code IRI_BACKEND_EXPECTED_AUDIENCES} went
 * missing from the host {@code .env}: nothing failed, the backend just started accepting every
 * validly-signed token of the realm, whatever client it was minted for. Under the {@code prod}
 * profile a blank value is therefore a configuration error and the context refuses to start. Every
 * other profile keeps "blank = off", logged at WARN so the state is visible.
 *
 * <p>The accepted audiences are logged at INFO on every start. They are client ids, not secrets.
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
   * @param environment the application environment, asked for the active profiles
   * @param expectedAudiences the raw {@code app.security.jwt.expected-audiences} comma list; blank
   *     entries are ignored
   * @throws IllegalStateException when the {@code prod} profile is active and no non-blank audience
   *     is configured, which aborts the application start
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
