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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * What the {@code azp} allow-list does at startup (ADR-0270, REQ-SEC-024): a blank list aborts
 * {@code prod}, and a configured list that no decoder will enforce is reported rather than claimed.
 */
class JwtAuthorizedPartyStartupCheckTest {

  /** The production list. */
  private static final List<String> ACCEPTED =
      List.of("basetool-frontend", "basetool-ingest-gateway");

  /** An audience list, which is one of the two things that build the decoder. */
  private static final List<String> AUDIENCES = List.of("basetool-backend");

  /**
   * An environment with one active profile.
   *
   * @param profile the profile to activate
   * @return the environment
   */
  private static MockEnvironment profile(String profile) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    return environment;
  }

  @Test
  @DisplayName("a blank list aborts the start under prod, naming the property")
  void aBlankListAbortsTheStartUnderProd() {
    assertThatThrownBy(
            () -> new JwtAuthorizedPartyStartupCheck(profile("prod"), List.of(" "), AUDIENCES, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(JwtAuthorizedPartyStartupCheck.PROPERTY);
  }

  @Test
  @DisplayName("a blank list outside prod leaves the list empty, which refuses every token")
  void aBlankListOutsideProdRefusesEveryToken() {
    assertThat(
            new JwtAuthorizedPartyStartupCheck(profile("dev"), List.of(), AUDIENCES, "")
                .getClientIds())
        .isEmpty();
  }

  @Test
  @DisplayName("the configured ids are trimmed and blanks dropped")
  void theConfiguredIdsAreTrimmed() {
    assertThat(
            new JwtAuthorizedPartyStartupCheck(
                    profile("dev"), List.of(" basetool-frontend ", "", " "), AUDIENCES, "")
                .getClientIds())
        .containsExactly("basetool-frontend");
  }

  @Test
  @DisplayName("an audience or a JWKS URL is what makes the list enforceable at all")
  void theListIsCarriedByTheDecoderEitherWayItIsBuilt() {
    assertThat(
            new JwtAuthorizedPartyStartupCheck(profile("dev"), ACCEPTED, AUDIENCES, "")
                .getClientIds())
        .isEqualTo(ACCEPTED);
    assertThat(
            new JwtAuthorizedPartyStartupCheck(
                    profile("dev"), ACCEPTED, List.of(), "https://keycloak.example/certs")
                .getClientIds())
        .isEqualTo(ACCEPTED);
    assertThat(
            new JwtAuthorizedPartyStartupCheck(profile("dev"), ACCEPTED, List.of(" "), "")
                .getClientIds())
        .as("the list is still read when nothing will enforce it; only the log line differs")
        .isEqualTo(ACCEPTED);
  }
}
