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

package de.greluc.krt.profit.basetool.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** The daily write quota's counter in Redis (REQ-XCH-023). */
@ExtendWith(MockitoExtension.class)
class ExchangeQuotasTest {

  private static final Instant NOW = Instant.parse("2026-09-27T22:00:00Z");
  private static final String KEY = "ingest:xch:quota:versekit:m-1:2026-09-27";
  private static final Duration UNTIL_THE_END_OF_TOMORROW = Duration.ofHours(26);
  private static final long BYTES = KEY.length() + (long) ExchangeQuotas.VALUE_BYTES;

  @Mock private StringRedisTemplate template;
  @Mock private ValueOperations<String, String> values;
  @Mock private ExchangeBudget budget;

  private ExchangeQuotas quotas;

  @BeforeEach
  void setUp() {
    quotas = new ExchangeQuotas(template, budget, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void theCounterIsCreatedWithItsExpiryBeforeItIsIncremented() {
    when(template.opsForValue()).thenReturn(values);
    when(values.increment(KEY)).thenReturn(1L);

    assertThat(quotas.countWrite("versekit", "m-1")).isEqualTo(new ExchangeQuotas.Counted(KEY, 1L));

    InOrder order = inOrder(budget, values);
    order.verify(budget).record("versekit", "m-1", KEY, BYTES, UNTIL_THE_END_OF_TOMORROW);
    order.verify(values).setIfAbsent(KEY, "0", UNTIL_THE_END_OF_TOMORROW);
    order.verify(values).increment(KEY);
    verify(template, never()).expire(anyString(), any(Duration.class));
  }

  @Test
  void laterWritesRegisterTheSameEntryUnderTheSameExpiry() {
    when(template.opsForValue()).thenReturn(values);
    when(values.increment(KEY)).thenReturn(7L);

    assertThat(quotas.countWrite("versekit", "m-1").count()).isEqualTo(7L);
    verify(budget).record("versekit", "m-1", KEY, BYTES, UNTIL_THE_END_OF_TOMORROW);
    verify(values).setIfAbsent(KEY, "0", UNTIL_THE_END_OF_TOMORROW);
  }

  @Test
  void aBudgetThatCannotBeReachedCountsNothing() {
    doThrow(new ExchangeUnavailableException("down", null))
        .when(budget)
        .record(anyString(), anyString(), anyString(), any(Long.class), any(Duration.class));

    assertThatThrownBy(() -> quotas.countWrite("versekit", "m-1"))
        .isInstanceOf(ExchangeUnavailableException.class);
    verify(template, never()).opsForValue();
  }

  @Test
  void anUnreachableRedisFailsClosed() {
    when(template.opsForValue()).thenReturn(values);
    when(values.increment(KEY)).thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> quotas.countWrite("versekit", "m-1"))
        .isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void aCounterRedisDoesNotReturnFailsClosed() {
    when(template.opsForValue()).thenReturn(values);
    when(values.increment(KEY)).thenReturn(null);

    assertThatThrownBy(() -> quotas.countWrite("versekit", "m-1"))
        .isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void theQuotaStartsOverAtUtcMidnight() {
    assertThat(quotas.secondsUntilTomorrow()).isEqualTo(2L * 3600L);
  }
}
