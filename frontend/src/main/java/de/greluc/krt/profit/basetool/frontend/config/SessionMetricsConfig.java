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

import de.greluc.krt.profit.basetool.frontend.metrics.ActiveSessionsTracker;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;

/**
 * Binds the {@code basetool_active_sessions} gauge (REQ-OBS-011) — the count of active Spring
 * Session sessions across all logged-in principals, a proxy for concurrent live users — to an
 * event-driven {@link ActiveSessionsTracker} rather than sampling the session registry on scrape.
 *
 * <p><b>Why not sample the registry:</b> the previous implementation sampled {@code
 * SpringSessionBackedSessionRegistry.getAllPrincipals()}, whose Redis-backed implementation
 * unconditionally throws {@code UnsupportedOperationException}. Micrometer caught that during gauge
 * sampling and reported {@code NaN} on every scrape, so {@code basetool_active_sessions} never
 * produced a value and the {@code SsePushChannelDead} alert (gated on {@code
 * basetool_active_sessions > 3}) could never fire — silently disarming the "push dead while users
 * online" detection (#1158). This config instead maintains the count from session-lifecycle events.
 *
 * <p><b>How the count stays correct:</b> the gauge reads {@link ActiveSessionsTracker#count()},
 * which is seeded once at {@link ApplicationReadyEvent} time from the Redis session namespace (so a
 * frontend restart does not blank the gauge for existing sessions) and then kept current by the
 * {@link SessionCreatedEvent} / {@link SessionDeletedEvent} / {@link SessionExpiredEvent} that
 * {@code RedisIndexedSessionRepository} publishes (create on first save; delete/expire via Redis
 * keyspace notifications, which {@code @EnableRedisIndexedHttpSession} enables by default).
 *
 * <p>Gated to the non-{@code test} profiles because the Redis session store it reads is
 * {@code @Profile("!test")} (see {@code RedisSessionConfig}); under the test profile the session
 * store is absent, so this config — and the gauge — simply do not load.
 */
@Configuration
@Profile("!test")
@Slf4j
public class SessionMetricsConfig {

  private final MeterRegistry meterRegistry;
  private final StringRedisTemplate redisTemplate;

  /**
   * Key prefix under which {@code RedisIndexedSessionRepository} stores one hash per session
   * ({@code <namespace>:sessions:<id>}); the startup seed scans {@code <prefix>*} and strips this
   * prefix to recover each id.
   */
  private final String sessionKeyPrefix;

  /**
   * Live session-id set behind the gauge; a plain (non-bean) holder so its logic is unit-testable.
   */
  private final ActiveSessionsTracker tracker = new ActiveSessionsTracker();

  /**
   * Creates the config with the meter registry, the Redis handle used to seed the initial count,
   * and the configured session namespace.
   *
   * @param meterRegistry the Micrometer registry the gauge is registered against.
   * @param redisTemplate the string Redis template used to scan the session namespace at startup.
   * @param redisNamespace the Spring Session Redis namespace (matches {@code RedisSessionConfig});
   *     session hash keys live under {@code <namespace>:sessions:<id>}.
   */
  public SessionMetricsConfig(
      MeterRegistry meterRegistry,
      StringRedisTemplate redisTemplate,
      @Value("${spring.session.redis.namespace:basetool:session}") String redisNamespace) {
    this.meterRegistry = meterRegistry;
    this.redisTemplate = redisTemplate;
    this.sessionKeyPrefix = redisNamespace + ":sessions:";
  }

  /**
   * Registers the active-session gauge, reading the live count from {@link #tracker}, at startup.
   */
  @PostConstruct
  void registerActiveSessionsGauge() {
    Gauge.builder(MetricNames.ACTIVE_SESSIONS, tracker, ActiveSessionsTracker::count)
        .description("Active Spring Session sessions across all logged-in principals.")
        .register(meterRegistry);
  }

  /**
   * Seeds the tracker once the context is ready with the sessions that already existed before this
   * instance started listening, so a frontend restart does not blank the gauge (and disarm the
   * alert) for the whole session TTL. Scans {@code <namespace>:sessions:*} and skips the
   * per-session {@code expires:<id>} marker keys so each session is counted exactly once. A Redis
   * error here is logged and swallowed — the gauge then simply starts from the live event stream.
   */
  @EventListener(ApplicationReadyEvent.class)
  void seedFromRedis() {
    try {
      Set<String> ids = new HashSet<>();
      ScanOptions options =
          ScanOptions.scanOptions().match(sessionKeyPrefix + "*").count(1000).build();
      try (Cursor<String> cursor = redisTemplate.scan(options)) {
        while (cursor.hasNext()) {
          String suffix = cursor.next().substring(sessionKeyPrefix.length());
          if (!suffix.startsWith("expires:")) {
            ids.add(suffix);
          }
        }
      }
      tracker.seed(ids);
      log.info("Seeded active-session gauge from Redis: {} live session(s).", ids.size());
    } catch (RuntimeException e) {
      log.warn(
          "Could not seed active-session gauge from Redis; starting from live events only.", e);
    }
  }

  /**
   * Adds a newly created session to the tracked count.
   *
   * @param event the Spring Session create event carrying the new session id.
   */
  @EventListener
  void onSessionCreated(SessionCreatedEvent event) {
    tracker.onSessionStarted(event.getSessionId());
  }

  /**
   * Removes an explicitly deleted (e.g. logout / concurrent-session eviction) session from the
   * tracked count.
   *
   * @param event the Spring Session delete event carrying the ended session id.
   */
  @EventListener
  void onSessionDeleted(SessionDeletedEvent event) {
    tracker.onSessionEnded(event.getSessionId());
  }

  /**
   * Removes an expired session from the tracked count.
   *
   * @param event the Spring Session expire event carrying the ended session id.
   */
  @EventListener
  void onSessionExpired(SessionExpiredEvent event) {
    tracker.onSessionEnded(event.getSessionId());
  }
}
