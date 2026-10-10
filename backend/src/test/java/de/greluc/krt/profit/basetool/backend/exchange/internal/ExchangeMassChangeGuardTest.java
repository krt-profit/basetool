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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeMassChangeGuardTest {

  private static final ExchangeCaller CALLER =
      new ExchangeCaller(UUID.randomUUID(), "versekit", "k".repeat(43));

  @Mock private ExchangeJournalService journalService;
  @InjectMocks private ExchangeMassChangeGuard guard;

  @ParameterizedTest(name = "window {0} + batch {1} of {2} -> {3}")
  @CsvSource({
    "0, 25, 1000, false",
    "0, 26, 1000, true",
    "20, 6, 1000, true",
    "25, 1, 1000, true",
    "0, 4, 5, false",
    "0, 5, 24, true",
    "0, 5, 25, false",
    "3, 2, 21, true",
    "3, 2, 22, false",
    "0, 1, 1, false"
  })
  void theWindowTripsAbove25OrAboveAFifthWithAtLeastFive(
      long inWindow, long batch, long current, boolean trips) {
    assertThat(ExchangeMassChangeGuard.trips(inWindow, batch, current)).isEqualTo(trips);
  }

  @Test
  void aBatchWithoutRemovalsNeverAsksAndNeverReadsTheJournal() {
    assertThat(guard.requiresConfirmation(CALLER, ExchangeResource.BLUEPRINT, 3, 0)).isFalse();

    verify(journalService, never()).removalsSince(any(), any(), any());
  }

  @Test
  void theWindowIsTheLast24HoursOfTheClientsRemovals() {
    ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
    when(journalService.removalsSince(eq(CALLER), eq(ExchangeResource.SHIP), since.capture()))
        .thenReturn(24L);

    assertThat(guard.requiresConfirmation(CALLER, ExchangeResource.SHIP, 1000, 2)).isTrue();

    assertThat(Duration.between(since.getValue(), Instant.now()))
        .isBetween(Duration.ofHours(24), Duration.ofHours(24).plusMinutes(1));
  }
}
