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

package de.greluc.krt.profit.basetool.ingest.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ExchangeRevocationReaderTest {

  @Mock private ValueOperations<String, String> values;
  @Mock private StringRedisTemplate template;

  private ExchangeRevocationReader reader;

  @BeforeEach
  void setUp() {
    when(template.opsForValue()).thenReturn(values);
    reader = new ExchangeRevocationReader(template);
  }

  @Test
  void aListedKeyIsDenied() {
    when(values.get("exchange:deny:jkt-a")).thenReturn("1790000000");
    when(values.get("exchange:deny:jkt-b")).thenReturn(null);

    assertThat(reader.isDenied("jkt-a")).isTrue();
    assertThat(reader.isDenied("jkt-b")).isFalse();
  }

  @Test
  void aRevocationIsReadAsItsEpochSecond() {
    when(values.get("exchange:revoked:versekit:m-1")).thenReturn("1790000000");
    when(values.get("exchange:revoked:versekit:m-2")).thenReturn(null);

    assertThat(reader.revokedAt("versekit", "m-1")).isEqualTo(1_790_000_000L);
    assertThat(reader.revokedAt("versekit", "m-2")).isNull();
  }

  @Test
  void anUnreadableRevocationFailsClosed() {
    when(values.get("exchange:revoked:versekit:m-1")).thenReturn("yesterday");

    assertThatThrownBy(() -> reader.revokedAt("versekit", "m-1"))
        .isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void anUnreachableRedisFailsClosed() {
    when(values.get("exchange:deny:jkt-a")).thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(() -> reader.isDenied("jkt-a"))
        .isInstanceOf(ExchangeUnavailableException.class);
  }
}
