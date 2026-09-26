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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.config.DiscordSpiPrecheckProperties;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.dto.DiscordAccountExistenceRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DiscordAccountExistenceResponse;
import de.greluc.krt.profit.basetool.backend.service.DiscordAccountExistenceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoint the Keycloak Discord SPI calls during first-broker-login to check whether a
 * Basetool account already exists for an incoming Discord identity (REQ-SEC-022, ADR-0051).
 *
 * <p>Lives outside {@code /api/**}, carries no JWT and is hidden from OpenAPI; this controller is
 * the sole gate via the constant-time shared secret {@code X-KRT-SPI-Secret}. Fail-open: a blank
 * secret answers {@code 503}, a wrong or absent one {@code 401}, and only {@code 200} with {@code
 * exists=true} denies a registration. Never logs the candidate names or e-mail.
 */
@RestController
@RequestMapping("/internal/discord/account-existence")
@RequiredArgsConstructor
@Slf4j
@Hidden
public class DiscordAccountExistenceController {

  /** Header carrying the shared secret the SPI must present. */
  static final String SECRET_HEADER = "X-KRT-SPI-Secret";

  private final DiscordAccountExistenceService accountExistenceService;
  private final DiscordSpiPrecheckProperties properties;
  private final MeterRegistry meterRegistry;

  /**
   * Answers whether an existing account collides with the supplied Discord identity.
   *
   * @param providedSecret the {@code X-KRT-SPI-Secret} header value; {@code null} when absent
   * @param request the candidate Discord username / e-mail / server nickname
   * @return {@code 200} with the existence flag on success; {@code 503} when the feature is not
   *     configured (blank secret); {@code 401} when the presented secret is absent or wrong
   */
  @PreAuthorize("permitAll()")
  @PostMapping
  public ResponseEntity<DiscordAccountExistenceResponse> checkAccountExistence(
      @RequestHeader(value = SECRET_HEADER, required = false) @Nullable String providedSecret,
      @RequestBody DiscordAccountExistenceRequest request) {
    String configuredSecret = properties.sharedSecret();
    if (configuredSecret == null || configuredSecret.isBlank()) {
      log.debug("Discord account-existence precheck called but no shared secret is configured.");
      recordPrecheck(MetricNames.DISCORD_PRECHECK_DISABLED);
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    if (!constantTimeEquals(configuredSecret, providedSecret)) {
      log.warn("Discord account-existence precheck rejected: missing or invalid shared secret.");
      recordPrecheck(MetricNames.DISCORD_PRECHECK_UNAUTHORIZED);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    boolean exists =
        accountExistenceService.accountExistsForDiscordIdentity(
            request.username(), request.email(), request.serverNickname());
    recordPrecheck(MetricNames.DISCORD_PRECHECK_OK);
    return ResponseEntity.ok(new DiscordAccountExistenceResponse(exists));
  }

  /**
   * Increments {@code basetool_discord_precheck_total} for one bounded {@code outcome}; never
   * records the candidate names or e-mail.
   *
   * @param outcome one of {@link MetricNames#DISCORD_PRECHECK_OK} / {@link
   *     MetricNames#DISCORD_PRECHECK_UNAUTHORIZED} / {@link MetricNames#DISCORD_PRECHECK_DISABLED}
   */
  private void recordPrecheck(@NotNull String outcome) {
    meterRegistry
        .counter(MetricNames.DISCORD_PRECHECK, MetricNames.TAG_OUTCOME, outcome)
        .increment();
  }

  /**
   * Constant-time comparison of the configured and presented secrets, so a timing side channel
   * cannot leak the secret. A {@code null} presented value never matches.
   *
   * @param expected the configured shared secret (non-blank at the call site)
   * @param provided the secret presented in the request header; may be {@code null}
   * @return {@code true} iff both are present and byte-for-byte equal
   */
  private static boolean constantTimeEquals(@NotNull String expected, @Nullable String provided) {
    if (provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
  }
}
