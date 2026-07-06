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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.DiscordSpiPrecheckProperties;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.dto.DiscordAccountExistenceRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DiscordAccountExistenceResponse;
import de.greluc.krt.profit.basetool.backend.service.DiscordAccountExistenceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link DiscordAccountExistenceController} (REQ-SEC-022): the shared-secret gate
 * (unconfigured → 503; missing/wrong → 401; valid → 200) and the boolean pass-through of the
 * existence decision. All cases keep the SPI fail-open contract: only a 200 with {@code
 * exists=true} denies a registration.
 */
@ExtendWith(MockitoExtension.class)
class DiscordAccountExistenceControllerTest {

  @Mock private DiscordAccountExistenceService accountExistenceService;
  @Mock private DiscordSpiPrecheckProperties properties;
  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();
  @InjectMocks private DiscordAccountExistenceController controller;

  private static final DiscordAccountExistenceRequest REQUEST =
      new DiscordAccountExistenceRequest("Maverick", "mav@example.com", "Mav");

  /**
   * Reads the {@code basetool_discord_precheck_total} value for one bounded {@code outcome}.
   *
   * @param outcome the bounded precheck outcome tag value
   * @return the counter value, or {@code 0.0} when the series is absent
   */
  private double precheckCount(String outcome) {
    var counter =
        meterRegistry
            .find(MetricNames.DISCORD_PRECHECK)
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void disabled_whenSecretNotConfigured_returns503_andDoesNotQuery() {
    when(properties.getSharedSecret()).thenReturn("");

    ResponseEntity<DiscordAccountExistenceResponse> response =
        controller.checkAccountExistence("anything", REQUEST);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    verifyNoInteractions(accountExistenceService);
    assertThat(precheckCount(MetricNames.DISCORD_PRECHECK_DISABLED)).isEqualTo(1.0);
  }

  @Test
  void rejects_whenSecretHeaderMissing_returns401() {
    when(properties.getSharedSecret()).thenReturn("real-secret");

    ResponseEntity<DiscordAccountExistenceResponse> response =
        controller.checkAccountExistence(null, REQUEST);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(accountExistenceService);
    assertThat(precheckCount(MetricNames.DISCORD_PRECHECK_UNAUTHORIZED)).isEqualTo(1.0);
  }

  @Test
  void rejects_whenSecretWrong_returns401() {
    when(properties.getSharedSecret()).thenReturn("real-secret");

    ResponseEntity<DiscordAccountExistenceResponse> response =
        controller.checkAccountExistence("wrong-secret", REQUEST);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verifyNoInteractions(accountExistenceService);
  }

  @Test
  void returnsExistsTrue_whenSecretValidAndAccountExists() {
    when(properties.getSharedSecret()).thenReturn("real-secret");
    when(accountExistenceService.accountExistsForDiscordIdentity(any(), any(), any()))
        .thenReturn(true);

    ResponseEntity<DiscordAccountExistenceResponse> response =
        controller.checkAccountExistence("real-secret", REQUEST);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().exists()).isTrue();
    assertThat(precheckCount(MetricNames.DISCORD_PRECHECK_OK)).isEqualTo(1.0);
  }

  @Test
  void returnsExistsFalse_whenSecretValidAndNoAccount() {
    when(properties.getSharedSecret()).thenReturn("real-secret");
    when(accountExistenceService.accountExistsForDiscordIdentity(any(), any(), any()))
        .thenReturn(false);

    ResponseEntity<DiscordAccountExistenceResponse> response =
        controller.checkAccountExistence("real-secret", REQUEST);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().exists()).isFalse();
  }
}
