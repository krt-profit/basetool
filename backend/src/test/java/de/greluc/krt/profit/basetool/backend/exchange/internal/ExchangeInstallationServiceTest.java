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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeInstallationConnectedEvent;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ExchangeInstallationServiceTest {

  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440d1");
  private static final UUID INSTALLATION = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000001d1");
  private static final String KEY = "Kx9_" + "d".repeat(39);

  private final ExchangeInstallationRepository installations =
      mock(ExchangeInstallationRepository.class);
  private final ExchangeClientRepository clients = mock(ExchangeClientRepository.class);
  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExchangeInstallationService service =
      new ExchangeInstallationService(installations, clients, publisher, meterRegistry);

  @BeforeEach
  void setUp() {
    ExchangeClient client = new ExchangeClient();
    client.setClientId("versekit");
    client.setDisplayName("VerseKit");
    when(clients.findWithCapabilitiesByClientId("versekit")).thenReturn(Optional.of(client));
  }

  @Test
  void aFirstSightAnnouncesTheConnectionByTheRegistryName() {
    touchReturns(List.of(touched(true)));

    service.touch("versekit", MEMBER, KEY);

    verify(publisher)
        .publishEvent(new ExchangeInstallationConnectedEvent(MEMBER, INSTALLATION, "VerseKit"));
    assertThat(created("versekit")).isEqualTo(1.0);
  }

  @Test
  void aKnownInstallationAnnouncesNothing() {
    touchReturns(List.of(touched(false)));

    service.touch("versekit", MEMBER, KEY);

    verify(publisher, never()).publishEvent(any(Object.class));
    assertThat(created("versekit")).isZero();
  }

  @Test
  void aTouchWithinTheIntervalAnnouncesNothing() {
    touchReturns(List.of());

    service.touch("versekit", MEMBER, KEY);

    verify(publisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void aClientMissingFromTheRegistryIsNamedByItsId() {
    touchReturns(List.of(touched(true)));
    when(clients.findWithCapabilitiesByClientId("gone")).thenReturn(Optional.empty());

    service.touch("gone", MEMBER, KEY);

    verify(publisher)
        .publishEvent(new ExchangeInstallationConnectedEvent(MEMBER, INSTALLATION, "gone"));
  }

  /**
   * Reads the installations-created counter of a client.
   *
   * @param clientId the client
   * @return the count, 0 before the first
   */
  private double created(@NotNull String clientId) {
    Counter counter =
        meterRegistry
            .find(MetricNames.EXCHANGE_INSTALLATIONS_CREATED)
            .tag(MetricNames.TAG_CLIENT_ID, clientId)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * Stubs the upsert's answer.
   *
   * @param rows the rows it returns
   */
  private void touchReturns(@NotNull List<ExchangeInstallationRepository.Touched> rows) {
    when(installations.touch(anyString(), eq(MEMBER), eq(KEY), any(Instant.class), any()))
        .thenReturn(rows);
  }

  /**
   * Builds one upsert row for {@link #INSTALLATION}.
   *
   * @param inserted whether the upsert created it
   * @return the row
   */
  private static @NotNull ExchangeInstallationRepository.Touched touched(boolean inserted) {
    return new ExchangeInstallationRepository.Touched() {
      @Override
      public UUID getId() {
        return INSTALLATION;
      }

      @Override
      public boolean getInserted() {
        return inserted;
      }
    };
  }
}
