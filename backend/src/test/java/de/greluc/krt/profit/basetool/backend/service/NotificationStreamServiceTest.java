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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit tests for {@link NotificationStreamService}: the registry must emit the {@code connected},
 * {@code heartbeat} and {@code notification} signals as <b>named</b> SSE events. The heartbeat in
 * particular must NOT be an SSE comment — browsers' {@code EventSource} swallow comments, so only a
 * named event lets the frontend reset its liveness watchdog and detect a half-open stream
 * (REQ-NOTIF-010, REQ-SEC-012).
 */
class NotificationStreamServiceTest {

  /**
   * A service whose emitters are a single shared mock, so a test can register a subscriber and then
   * assert on the exact SSE events the registry sends.
   */
  private static final class CapturingStreamService extends NotificationStreamService {
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final SimpleMeterRegistry registry;

    CapturingStreamService() {
      this(new SimpleMeterRegistry());
    }

    private CapturingStreamService(SimpleMeterRegistry registry) {
      super(registry);
      this.registry = registry;
    }

    @Override
    protected SseEmitter newEmitter() {
      return emitter;
    }
  }

  /**
   * A service whose {@link #newEmitter()} yields a fresh distinct mock per subscription, recorded
   * in {@link #created} in subscription order — so a test can assert WHICH emitter the per-user cap
   * retires (#1156).
   */
  private static final class DistinctEmitterStreamService extends NotificationStreamService {
    private final List<SseEmitter> created = new ArrayList<>();
    private final SimpleMeterRegistry registry;

    DistinctEmitterStreamService() {
      this(new SimpleMeterRegistry());
    }

    private DistinctEmitterStreamService(SimpleMeterRegistry registry) {
      super(registry);
      this.registry = registry;
    }

    @Override
    protected SseEmitter newEmitter() {
      SseEmitter e = mock(SseEmitter.class);
      created.add(e);
      return e;
    }
  }

  /** Renders an SSE event builder to its wire form so assertions can inspect the event name. */
  private static String render(SseEmitter.SseEventBuilder builder) {
    StringBuilder sb = new StringBuilder();
    builder.build().forEach(part -> sb.append(String.valueOf(part.getData())));
    return sb.toString();
  }

  @Test
  void heartbeat_sendsNamedHeartbeatEvent_notAComment() throws Exception {
    // Given a registered subscriber
    CapturingStreamService service = new CapturingStreamService();
    service.subscribe(UUID.randomUUID());
    clearInvocations(service.emitter); // drop the `connected` send from subscribe()

    // When the scheduled heartbeat fires
    service.heartbeat();

    // Then a NAMED `heartbeat` event is sent (not a `:heartbeat` comment that EventSource swallows)
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(service.emitter).send(captor.capture());
    String wire = render(captor.getValue());
    assertTrue(
        wire.contains("event:heartbeat"),
        "heartbeat must be a named event so the browser EventSource can observe it: " + wire);
    assertTrue(
        wire.contains("data:ok"),
        "a named SSE event must carry a data field or EventSource discards it without dispatching, "
            + "defeating the liveness signal: "
            + wire);
    assertFalse(
        wire.contains(":heartbeat\n") && !wire.contains("event:heartbeat"),
        "heartbeat must not be an SSE comment");
  }

  @Test
  void subscribe_sendsNamedConnectedEvent() throws Exception {
    // Given/When a browser subscribes
    CapturingStreamService service = new CapturingStreamService();
    service.subscribe(UUID.randomUUID());

    // Then a named `connected` handshake event is sent
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(service.emitter).send(captor.capture());
    assertTrue(render(captor.getValue()).contains("event:connected"));
  }

  @Test
  void publish_sendsNamedNotificationEventToSubscribersOfThatRecipient() throws Exception {
    // Given a subscriber for a specific recipient
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientSub = UUID.randomUUID();
    service.subscribe(recipientSub);
    clearInvocations(service.emitter); // drop the `connected` send from subscribe()

    // When a notification is published to that recipient
    service.publish(List.of(recipientSub));

    // Then a named `notification` event is pushed so the client refreshes its unread state
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(service.emitter).send(captor.capture());
    assertTrue(render(captor.getValue()).contains("event:notification"));
  }

  @Test
  void onTimeout_completesEmitter_soSpringRecordsCleanCompletionNotPhantom503() {
    // Given a subscribed emitter, capture the timeout callback the registry registered on it.
    CapturingStreamService service = new CapturingStreamService();
    service.subscribe(UUID.randomUUID());
    ArgumentCaptor<Runnable> timeoutCallback = ArgumentCaptor.forClass(Runnable.class);
    verify(service.emitter).onTimeout(timeoutCallback.capture());

    // When the 30-minute SSE timeout fires
    timeoutCallback.getValue().run();

    // Then the emitter is completed, so Spring MVC finalizes the async request as a NORMAL
    // completion
    // instead of raising AsyncRequestTimeoutException — which Micrometer would otherwise book as a
    // phantom 503 on http.server.requests even though the client had a clean stream
    // (REQ-NOTIF-010).
    verify(service.emitter).complete();
  }

  @Test
  void subscribe_reflectsLiveConnectionsInSseConnectionsGauge() {
    // Given a fresh registry the gauge reads zero
    CapturingStreamService service = new CapturingStreamService();
    assertEquals(0.0, sseConnections(service));

    // When a subscriber connects (the mock emitter's `connected` send succeeds by default)
    service.subscribe(UUID.randomUUID());

    // Then the summed-emitter gauge reports one live connection
    assertEquals(1.0, sseConnections(service));
  }

  @Test
  void publish_sendFailure_recordsSseSendFailureCounterAndDropsEmitter() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientSub = UUID.randomUUID();
    service.subscribe(recipientSub); // `connected` send succeeds, emitter registered
    clearInvocations(service.emitter);
    doThrow(new IOException("broken pipe"))
        .when(service.emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    // When the notification push fails on a dead emitter
    service.publish(List.of(recipientSub));

    // Then the failure is counted under the `notification` event and the emitter is dropped
    assertEquals(
        1.0,
        service
            .registry
            .get(MetricNames.SSE_SEND_FAILURES)
            .tag(MetricNames.TAG_EVENT, MetricNames.SSE_EVENT_NOTIFICATION)
            .counter()
            .count());
    assertEquals(0.0, sseConnections(service));
  }

  @Test
  void subscribe_capsEmittersPerSub_retiresOldestWithNamedReplacedEvent() throws Exception {
    // #1156: one subscription past the per-user cap (all of a user's tabs/devices on one sub).
    DistinctEmitterStreamService service = new DistinctEmitterStreamService();
    UUID sub = UUID.randomUUID();
    int count = NotificationStreamService.MAX_EMITTERS_PER_SUB + 1;
    for (int i = 0; i < count; i++) {
      service.subscribe(sub);
    }

    // The registry holds exactly the cap — the extra subscription evicted the oldest, not grew it.
    assertEquals(
        (double) NotificationStreamService.MAX_EMITTERS_PER_SUB,
        service.registry.get(MetricNames.SSE_CONNECTIONS).gauge().value());

    // The OLDEST emitter (first subscribed) is retired: a terminal named `replaced` event (which
    // the
    // client treats as do-not-reconnect) followed by complete().
    SseEmitter oldest = service.created.get(0);
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(oldest, atLeastOnce()).send(captor.capture());
    assertThat(captor.getAllValues())
        .anySatisfy(builder -> assertThat(render(builder)).contains("event:replaced"));
    verify(oldest).complete();

    // The newest (retained) emitter is not retired.
    verify(service.created.get(count - 1), never()).complete();
  }

  /**
   * Reads the {@code basetool_sse_connections} gauge value from the service's registry.
   *
   * @param service the capturing service under test
   * @return the current summed-connection gauge value
   */
  private static double sseConnections(CapturingStreamService service) {
    return service.registry.get(MetricNames.SSE_CONNECTIONS).gauge().value();
  }
}
