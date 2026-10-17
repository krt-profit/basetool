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

package de.greluc.krt.profit.basetool.backend.platform.api;

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
 * Validates the JWT {@code azp} allow-list at startup (ADR-0270, REQ-SEC-024).
 *
 * <p>Under the {@code prod} profile a blank {@code app.security.jwt.accepted-client-ids} aborts the
 * start. Unlike the audience list, blank is never "no check": an empty list names no client, so
 * every token is refused.
 */
@Slf4j
@Component
public class JwtAuthorizedPartyStartupCheck {

  /** The property the list is read from, named in the failure message. */
  public static final String PROPERTY = "app.security.jwt.accepted-client-ids";

  /** The profile under which a blank list refuses the start. */
  static final String PROD_PROFILE = "prod";

  /** The effective, trimmed, non-blank client ids a token's {@code azp} must name. */
  @Getter @NotNull @Unmodifiable private final List<String> clientIds;

  /**
   * Evaluates the configured client ids once, at context start.
   *
   * @param environment the environment, queried for the active profiles
   * @param acceptedClientIds the raw comma-separated client-id list; blank entries are ignored
   * @throws IllegalStateException when {@code prod} is active and no client id is configured
   */
  public JwtAuthorizedPartyStartupCheck(
      @NotNull Environment environment,
      @Value("${" + PROPERTY + ":}") @NotNull List<String> acceptedClientIds) {
    this.clientIds =
        acceptedClientIds.stream().filter(StringUtils::hasText).map(String::trim).toList();
    if (!clientIds.isEmpty()) {
      log.info("JWT azp check enforced; accepted client ids: {}", clientIds);
      return;
    }
    if (environment.matchesProfiles(PROD_PROFILE)) {
      throw new IllegalStateException(
          PROPERTY
              + " is blank under the prod profile. Set it to the clients that may call this"
              + " backend directly (normally basetool-frontend,basetool-ingest-gateway): an empty"
              + " list names no client, so every token would be refused.");
    }
    log.warn(
        "JWT azp allow-list is empty ({} is blank); every token is refused. Allowed outside the"
            + " prod profile only.",
        PROPERTY);
  }
}
