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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.MapSession;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;

/**
 * Tests {@link SessionMetricsConfig}'s gauge wiring (#1158): the {@code basetool_active_sessions}
 * gauge must sample a <em>finite</em> value (the whole point of the fix — the old registry-sampling
 * implementation reported {@code NaN}), seed correctly from the Redis session namespace, and track
 * create / delete / expire events. Redis is mocked (no live store needed); the config's own
 * {@code @Profile("!test")} gating is bypassed by driving the bean directly.
 */
class SessionMetricsConfigTest {

  private static final String NS = "basetool:session";

  private SimpleMeterRegistry registry;
  private StringRedisTemplate redisTemplate;
  private SessionMetricsConfig config;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    redisTemplate = mock(StringRedisTemplate.class);
    config = new SessionMetricsConfig(registry, redisTemplate, NS);
    config.registerActiveSessionsGauge();
  }

  private double gauge() {
    return registry.get(MetricNames.ACTIVE_SESSIONS).gauge().value();
  }

  @Test
  void gaugeIsFiniteAndZeroBeforeAnySession() {
    assertThat(gauge()).isEqualTo(0.0);
  }

  @Test
  void seedCountsSessionHashKeysAndSkipsExpiresMarkers() {
    Cursor<String> cursor = mock();
    when(cursor.hasNext()).thenReturn(true, true, true, false);
    when(cursor.next())
        .thenReturn(NS + ":sessions:aaaa", NS + ":sessions:expires:aaaa", NS + ":sessions:bbbb");
    when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

    config.seedFromRedis();

    // aaaa + bbbb counted; the per-session `expires:` marker key is skipped -> a finite 2, not NaN.
    assertThat(gauge()).isEqualTo(2.0);
  }

  @Test
  void seedFailureLeavesGaugeFiniteAtZero() {
    when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("redis down"));

    config.seedFromRedis();

    assertThat(gauge()).isEqualTo(0.0);
  }

  @Test
  void createAndEndEventsMoveTheGauge() {
    config.onSessionCreated(new SessionCreatedEvent(this, new MapSession("s1")));
    config.onSessionCreated(new SessionCreatedEvent(this, new MapSession("s2")));
    assertThat(gauge()).isEqualTo(2.0);

    config.onSessionDeleted(new SessionDeletedEvent(this, new MapSession("s1")));
    assertThat(gauge()).isEqualTo(1.0);

    config.onSessionExpired(new SessionExpiredEvent(this, new MapSession("s2")));
    assertThat(gauge()).isEqualTo(0.0);
  }
}
