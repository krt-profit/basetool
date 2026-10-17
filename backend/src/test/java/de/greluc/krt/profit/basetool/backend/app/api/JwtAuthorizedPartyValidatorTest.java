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

package de.greluc.krt.profit.basetool.backend.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The {@code azp} allow-list: which client a token may have been minted for (ADR-0270,
 * REQ-SEC-024). What the list does at startup is {@code JwtAuthorizedPartyStartupCheckTest}.
 */
class JwtAuthorizedPartyValidatorTest {

  /** The production list. */
  private static final List<String> ACCEPTED =
      List.of("basetool-frontend", "basetool-ingest-gateway");

  /**
   * A token carrying the given authorized party.
   *
   * @param azp the client id to put in the claim, or {@code null} to omit the claim entirely
   * @return the token
   */
  private static @NotNull Jwt tokenFrom(@Nullable String azp) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
    if (azp != null) {
      builder.claim(SecurityConfig.AUTHORIZED_PARTY_CLAIM, azp);
    }
    return builder.build();
  }

  @Test
  @DisplayName("a token from a named client passes")
  void aNamedClientPasses() {
    assertThat(
            SecurityConfig.authorizedPartyValidator(ACCEPTED)
                .validate(tokenFrom("basetool-frontend"))
                .hasErrors())
        .isFalse();
    assertThat(
            SecurityConfig.authorizedPartyValidator(ACCEPTED)
                .validate(tokenFrom("basetool-ingest-gateway"))
                .hasErrors())
        .isFalse();
  }

  @Test
  @DisplayName("a realm client that is not named is refused, however valid its signature")
  void anUnnamedRealmClientIsRefused() {
    assertThat(
            SecurityConfig.authorizedPartyValidator(ACCEPTED)
                .validate(tokenFrom("basetool-sc-extractor"))
                .hasErrors())
        .as(
            "the extractor talks to the gateway, never to this backend; the audience mapper alone"
                + " would have let it in (ADR-0270)")
        .isTrue();
    assertThat(
            SecurityConfig.authorizedPartyValidator(ACCEPTED)
                .validate(tokenFrom("basetool-android"))
                .hasErrors())
        .isTrue();
  }

  @Test
  @DisplayName("a token without an azp claim is refused")
  void aTokenWithoutAzpIsRefused() {
    assertThat(
            SecurityConfig.authorizedPartyValidator(ACCEPTED).validate(tokenFrom(null)).hasErrors())
        .as(
            "Keycloak sets azp on every token it issues to a client, so an absent claim is not a"
                + " shape this deployment produces")
        .isTrue();
  }

  @Test
  @DisplayName("an empty list names no client, so it refuses rather than disabling the check")
  void anEmptyListRefusesEveryToken() {
    assertThat(
            SecurityConfig.authorizedPartyValidator(List.of())
                .validate(tokenFrom("basetool-frontend"))
                .hasErrors())
        .isTrue();
  }
}
