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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Override
    protected SseEmitter newEmitter() {
      return emitter;
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
}
