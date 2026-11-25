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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * The browser-to-backend notification SSE relays open on this instance (REQ-NOTIF-010), each a
 * browser-facing emitter with the backend subscription that feeds it.
 *
 * <p>As a {@link SmartLifecycle} in the default phase it stops before the web server's graceful
 * shutdown: it cancels every backend subscription, completes every browser stream and refuses new
 * relays, so no relay holds that shutdown for its whole phase. The browser reconnects on its own.
 */
@Component
@Slf4j
public class NotificationRelays implements SmartLifecycle {

  /** The open relays: each browser-facing emitter with the backend subscription feeding it. */
  private final Map<SseEmitter, Disposable> open = new ConcurrentHashMap<>();

  /** Whether the instance is shutting down: its relays were closed and a new one is refused. */
  private volatile boolean closed;

  /**
   * Binds the {@code basetool_notification_relay_connections} gauge to the number of open relays.
   *
   * @param meterRegistry the registry the gauge binds to
   */
  public NotificationRelays(@NotNull MeterRegistry meterRegistry) {
    Gauge.builder(MetricNames.NOTIFICATION_RELAY_CONNECTIONS, open, Map::size)
        .description("Open browser-to-backend notification SSE relays on this instance.")
        .register(meterRegistry);
  }

  /**
   * Admits a relay unless the instance shuts down.
   *
   * @param emitter the browser-facing emitter
   * @param upstream the backend subscription feeding it, cancelled when the relay is closed
   * @return {@code true} when the relay is open and tracked; {@code false} while the instance shuts
   *     down, when the caller completes the emitter without subscribing upstream
   */
  public boolean admit(@NotNull SseEmitter emitter, @NotNull Disposable upstream) {
    open.put(emitter, upstream);
    if (closed) {
      open.remove(emitter);
      return false;
    }
    return true;
  }

  /**
   * Forgets a relay that ended on its own; a relay that is not tracked is ignored.
   *
   * @param emitter the browser-facing emitter of the relay
   */
  public void release(@NotNull SseEmitter emitter) {
    open.remove(emitter);
  }

  /** Admits relays again; the registry admits them from construction on. */
  @Override
  public void start() {
    closed = false;
  }

  /**
   * Refuses new relays, cancels every backend subscription and completes every browser stream,
   * before the web server's graceful shutdown waits for in-flight requests.
   */
  @Override
  public void stop() {
    closed = true;
    int count = open.size();
    open.forEach((emitter, upstream) -> {
      upstream.dispose();
      completeQuietly(emitter);
      open.remove(emitter);
    });
    log.info("Closed {} notification SSE relay(s) ahead of the graceful shutdown", count);
  }

  /**
   * Reports whether the registry admits relays, which it does until {@link #stop()}.
   *
   * @return {@code true} until the instance shuts down
   */
  @Override
  public boolean isRunning() {
    return !closed;
  }

  /**
   * Completes the emitter from a non-container thread, tolerating a request the container has
   * already ended.
   *
   * @param emitter the browser-facing emitter to complete
   */
  static void completeQuietly(@NotNull SseEmitter emitter) {
    try {
      emitter.complete();
    } catch (IllegalStateException e) {
      log.debug(
          "Notification stream already ended by the container ({}); nothing to complete",
          e.getClass().getSimpleName());
    }
  }
}
