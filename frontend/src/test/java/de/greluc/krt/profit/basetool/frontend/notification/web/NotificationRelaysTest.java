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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * Unit tests for {@link NotificationRelays}: the open relays are counted, and the shutdown closes
 * them and refuses new ones before the web server's graceful shutdown waits (REQ-NOTIF-010).
 */
class NotificationRelaysTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final NotificationRelays relays = new NotificationRelays(registry);

  @Test
  void admit_countsTheRelay_andReleaseForgetsIt() {
    SseEmitter emitter = mock(SseEmitter.class);

    assertTrue(relays.admit(emitter, mock(Disposable.class)));
    assertEquals(1.0, openRelays());

    relays.release(emitter);
    relays.release(emitter);
    assertEquals(0.0, openRelays());
  }

  @Test
  void stop_cancelsEveryUpstream_completesEveryBrowserStream_andRefusesNewRelays() {
    SseEmitter first = mock(SseEmitter.class);
    SseEmitter second = mock(SseEmitter.class);
    Disposable firstUpstream = mock(Disposable.class);
    Disposable secondUpstream = mock(Disposable.class);
    relays.admit(first, firstUpstream);
    relays.admit(second, secondUpstream);

    relays.stop();

    verify(firstUpstream).dispose();
    verify(secondUpstream).dispose();
    verify(first).complete();
    verify(second).complete();
    assertEquals(0.0, openRelays());
    assertFalse(relays.isRunning());
    SseEmitter late = mock(SseEmitter.class);
    assertFalse(relays.admit(late, mock(Disposable.class)), "a relay opened during the shutdown");
    assertEquals(0.0, openRelays());
    verify(late, never()).complete();
  }

  @Test
  void start_afterStop_admitsRelaysAgain() {
    relays.stop();

    relays.start();

    assertTrue(relays.isRunning());
    assertTrue(relays.admit(mock(SseEmitter.class), mock(Disposable.class)));
  }

  @Test
  void completeQuietly_toleratesARequestTheContainerAlreadyEnded() {
    SseEmitter ended = mock(SseEmitter.class);
    doThrow(new IllegalStateException("AsyncContext no longer usable")).when(ended).complete();

    assertDoesNotThrow(() -> NotificationRelays.completeQuietly(ended));
  }

  @Test
  void stopsBeforeTheWebServersGracefulShutdown_soNoRelayHoldsItsPhase() {
    WebServerGracefulShutdownLifecycle gracefulShutdown =
        new WebServerGracefulShutdownLifecycle(mock(WebServer.class));

    assertTrue(
        relays.getPhase() > gracefulShutdown.getPhase(),
        "a higher phase stops first: " + relays.getPhase() + " vs " + gracefulShutdown.getPhase());
    assertTrue(relays.isAutoStartup());
  }

  private double openRelays() {
    return registry.get(MetricNames.NOTIFICATION_RELAY_CONNECTIONS).gauge().value();
  }
}
