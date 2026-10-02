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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeGatewayProperties;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRegistryMirrorAge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ExchangeRegistryReaderTest {

  private static final String KEY = "exchange:registry";
  private static final String DOCUMENT =
      "{\"schemaVersion\":1,\"revision\":7,\"writtenAt\":\"2026-09-27T12:00:00Z\",\"enabled\":true,"
          + "\"clients\":{\"versekit\":{\"displayName\":\"VerseKit\",\"status\":\"ACTIVE\","
          + "\"capabilities\":[\"exchange.connect\",\"exchange.stock.read\"],"
          + "\"minClientVersion\":\"2.4.0\",\"requestsPerMinute\":120,\"writesPerDay\":null},"
          + "\"old\":{\"displayName\":\"Old\",\"status\":\"SUSPENDED\",\"capabilities\":[]}}}";

  @Mock private ValueOperations<String, String> values;
  @Mock private StringRedisTemplate template;

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-27T12:00:00Z"));
  private ExchangeRegistryReader reader;

  @BeforeEach
  void setUp() {
    when(template.opsForValue()).thenReturn(values);
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of("UTC");
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    reader =
        new ExchangeRegistryReader(
            template,
            JsonMapper.builder().build(),
            new ExchangeGatewayProperties(
                KEY, Duration.ofSeconds(5), "https://docs.example/exchange"),
            clock);
  }

  @Test
  void theDocumentIsParsed() {
    when(values.get(KEY)).thenReturn(DOCUMENT);

    ExchangeRegistry registry = reader.current();

    assertThat(registry.revision()).isEqualTo(7L);
    assertThat(registry.enabled()).isTrue();
    ExchangeRegistry.Client client = registry.clients().get("versekit");
    assertThat(client.active()).isTrue();
    assertThat(client.displayName()).isEqualTo("VerseKit");
    assertThat(client.capabilities())
        .containsExactlyInAnyOrder("exchange.connect", "exchange.stock.read");
    assertThat(client.minClientVersion()).isEqualTo("2.4.0");
    assertThat(client.requestsPerMinute()).isEqualTo(120);
    assertThat(client.writesPerDay()).isNull();
    assertThat(registry.clients().get("old").active()).isFalse();
  }

  @Test
  void theRegistryIsCachedForItsLifetimeOnly() {
    when(values.get(KEY)).thenReturn(DOCUMENT);

    reader.current();
    now.set(now.get().plusSeconds(4));
    reader.current();
    verify(values, times(1)).get(KEY);

    now.set(now.get().plusSeconds(2));
    reader.current();
    verify(values, times(2)).get(KEY);
  }

  @Test
  void aMissingDocumentFailsClosed() {
    when(values.get(KEY)).thenReturn(null);

    assertThatThrownBy(reader::current).isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void anUnknownFormatFailsClosed() {
    when(values.get(KEY)).thenReturn("{\"schemaVersion\":2,\"enabled\":true,\"clients\":{}}");

    assertThatThrownBy(reader::current).isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void garbageFailsClosed() {
    when(values.get(KEY)).thenReturn("not json");

    assertThatThrownBy(reader::current).isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void anUnreachableRedisFailsClosed() {
    when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));

    assertThatThrownBy(reader::current).isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void oddShapesDegradeToSafeDefaults() {
    when(values.get(KEY))
        .thenReturn(
            "{\"schemaVersion\":1,\"enabled\":\"yes\",\"clients\":{\"x\":{\"status\":\"ACTIVE\","
                + "\"capabilities\":[\"exchange.connect\",7],\"requestsPerMinute\":\"many\","
                + "\"minClientVersion\":3}}}");

    ExchangeRegistry registry = reader.current();

    assertThat(registry.enabled()).isFalse();
    assertThat(registry.revision()).isZero();
    ExchangeRegistry.Client client = registry.clients().get("x");
    assertThat(client.displayName()).isEmpty();
    assertThat(client.capabilities()).containsExactly("exchange.connect");
    assertThat(client.requestsPerMinute()).isNull();
    assertThat(client.minClientVersion()).isNull();
  }

  @Test
  void aDocumentWithoutClientsHasNone() {
    when(values.get(KEY)).thenReturn("{\"schemaVersion\":1,\"enabled\":true,\"clients\":[]}");

    assertThat(reader.current().clients()).isEmpty();
  }

  @Test
  void aDocumentWithoutAVersionFailsClosed() {
    when(values.get(KEY)).thenReturn("{\"enabled\":true}");

    assertThatThrownBy(reader::current).isInstanceOf(ExchangeUnavailableException.class);
  }

  @Test
  void theMirrorAgeCountsFromTheLastSuccessfulRead() {
    when(values.get(KEY))
        .thenReturn(DOCUMENT)
        .thenThrow(new RedisConnectionFailureException("down"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ExchangeRegistryMirrorAge age = new ExchangeRegistryMirrorAge(reader, meterRegistry);
    age.register();

    assertThat(mirrorAge(meterRegistry)).as("before the first read").isNaN();

    age.refresh();
    now.set(now.get().plusSeconds(90));
    assertThat(mirrorAge(meterRegistry)).isEqualTo(90.0d);

    age.refresh();
    now.set(now.get().plusSeconds(30));
    assertThat(mirrorAge(meterRegistry))
        .as("a failed read leaves the last good one in place")
        .isEqualTo(120.0d);
  }

  @Test
  void aFailedReadIsNotCached() {
    when(values.get(KEY)).thenReturn(null).thenReturn(DOCUMENT);

    assertThatThrownBy(reader::current).isInstanceOf(ExchangeUnavailableException.class);
    assertThat(reader.current().enabled()).isTrue();
  }

  /**
   * Reads the mirror-age gauge.
   *
   * @param meterRegistry the registry it was published to
   * @return the gauge's value
   */
  private static double mirrorAge(SimpleMeterRegistry meterRegistry) {
    return meterRegistry.get("basetool.exchange.registry.mirror.age").gauge().value();
  }
}
