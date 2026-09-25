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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit tests for {@link NotificationStreamService}: {@code connected}, {@code heartbeat} and {@code
 * notification} are sent as named SSE events, never as comments (REQ-NOTIF-010).
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
   * Service whose {@link #newEmitter()} returns a distinct mock per subscription, recorded in
   * {@link #created} in order.
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
    CapturingStreamService service = new CapturingStreamService();
    service.subscribe(UUID.randomUUID());
    clearInvocations(service.emitter);

    service.heartbeat();

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
    CapturingStreamService service = new CapturingStreamService();
    service.subscribe(UUID.randomUUID());

    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(service.emitter).send(captor.capture());
    assertTrue(render(captor.getValue()).contains("event:connected"));
  }

  @Test
  void publish_sendsNamedNotificationEventToSubscribersOfThatRecipient() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    service.subscribe(recipientUserId);
    clearInvocations(service.emitter);

    service.publish(List.of(recipientUserId), NotificationSignal.refreshOnly());

    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(service.emitter).send(captor.capture());
    assertTrue(render(captor.getValue()).contains("event:notification"));
  }

  @Test
  void publish_refreshOnlySignal_keepsTheHistoricPayload() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    service.subscribe(recipientUserId);
    clearInvocations(service.emitter);

    service.publish(List.of(recipientUserId), NotificationSignal.refreshOnly());

    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(service.emitter).send(captor.capture());
    assertTrue(render(captor.getValue()).contains("data:new"));
  }

  @Test
  void publish_signalWithAType_carriesKindEntityAndParams() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();
    service.subscribe(recipientUserId);
    clearInvocations(service.emitter);

    service.publish(
        List.of(recipientUserId),
        new NotificationSignal(
            NotificationType.DISCORD_REGISTRATION_PENDING,
            "USER",
            entityId,
            Map.of("username", "newbie")));

    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(service.emitter).send(captor.capture());
    String rendered = render(captor.getValue());
    assertTrue(rendered.contains("event:notification"));
    assertTrue(rendered.contains("DISCORD_REGISTRATION_PENDING"));
    assertTrue(rendered.contains(entityId.toString()));
    assertTrue(rendered.contains("newbie"));
  }

  @Test
  void onTimeout_completesEmitter_soSpringRecordsCleanCompletionNotPhantom503() {
    CapturingStreamService service = new CapturingStreamService();
    service.subscribe(UUID.randomUUID());
    ArgumentCaptor<Runnable> timeoutCallback = ArgumentCaptor.forClass(Runnable.class);
    verify(service.emitter).onTimeout(timeoutCallback.capture());

    timeoutCallback.getValue().run();

    verify(service.emitter).complete();
  }

  @Test
  void subscribe_reflectsLiveConnectionsInSseConnectionsGauge() {
    CapturingStreamService service = new CapturingStreamService();
    assertEquals(0.0, sseConnections(service));

    service.subscribe(UUID.randomUUID());

    assertEquals(1.0, sseConnections(service));
  }

  @Test
  void publish_sendFailure_recordsSseSendFailureCounterAndDropsEmitter() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    service.subscribe(recipientUserId);
    clearInvocations(service.emitter);
    doThrow(new IOException("broken pipe"))
        .when(service.emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    service.publish(List.of(recipientUserId), NotificationSignal.refreshOnly());

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
    DistinctEmitterStreamService service = new DistinctEmitterStreamService();
    UUID sub = UUID.randomUUID();
    int count = NotificationStreamService.MAX_EMITTERS_PER_SUB + 1;
    for (int i = 0; i < count; i++) {
      service.subscribe(sub);
    }

    assertEquals(
        (double) NotificationStreamService.MAX_EMITTERS_PER_SUB,
        service.registry.get(MetricNames.SSE_CONNECTIONS).gauge().value());

    SseEmitter oldest = service.created.get(0);
    ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
        ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
    verify(oldest, atLeastOnce()).send(captor.capture());
    assertThat(captor.getAllValues())
        .anySatisfy(builder -> assertThat(render(builder)).contains("event:replaced"));
    verify(oldest).complete();

    verify(service.created.get(count - 1), never()).complete();
  }

  @Test
  void subscribe_connectedSendFailure_countsUnderConnectedAndDoesNotRegister() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    doThrow(new IOException("broken pipe"))
        .when(service.emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    service.subscribe(UUID.randomUUID());

    assertEquals(
        1.0,
        service
            .registry
            .get(MetricNames.SSE_SEND_FAILURES)
            .tag(MetricNames.TAG_EVENT, MetricNames.SSE_EVENT_CONNECTED)
            .counter()
            .count());
    assertEquals(0.0, sseConnections(service));
  }

  @Test
  void heartbeat_sendFailure_countsUnderHeartbeatAndDropsEmitter() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    service.subscribe(recipientUserId);
    clearInvocations(service.emitter);
    doThrow(new IOException("broken pipe"))
        .when(service.emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    service.heartbeat();

    assertEquals(
        1.0,
        service
            .registry
            .get(MetricNames.SSE_SEND_FAILURES)
            .tag(MetricNames.TAG_EVENT, MetricNames.SSE_EVENT_HEARTBEAT)
            .counter()
            .count());
    assertEquals(0.0, sseConnections(service));
  }

  @Test
  void sendFailure_tagsTheCauseAndLeavesTheThrowableInADebugLine() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    service.subscribe(recipientUserId);
    clearInvocations(service.emitter);
    IOException brokenPipe = new IOException("broken pipe");
    doThrow(brokenPipe).when(service.emitter).send(any(SseEmitter.SseEventBuilder.class));

    List<ILoggingEvent> events =
        withStreamLogAppender(
            () -> service.publish(List.of(recipientUserId), NotificationSignal.refreshOnly()));

    assertEquals(
        1.0,
        service
            .registry
            .get(MetricNames.SSE_SEND_FAILURES)
            .tag(MetricNames.TAG_EVENT, MetricNames.SSE_EVENT_NOTIFICATION)
            .tag(MetricNames.TAG_CAUSE, MetricNames.CAUSE_IO)
            .counter()
            .count());
    ILoggingEvent logged = events.getLast();
    assertEquals(Level.DEBUG, logged.getLevel());
    assertNotNull(logged.getThrowableProxy(), "the caught exception must reach the log line");
    assertTrue(logged.getFormattedMessage().contains(recipientUserId.toString()));
  }

  @Test
  void sendFailure_onAnAlreadyCompletedEmitter_isCountedAsIllegalStateNotIo() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    service.subscribe(recipientUserId);
    clearInvocations(service.emitter);
    doThrow(new IllegalStateException("already completed"))
        .when(service.emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    service.heartbeat();

    assertEquals(
        1.0,
        service
            .registry
            .get(MetricNames.SSE_SEND_FAILURES)
            .tag(MetricNames.TAG_EVENT, MetricNames.SSE_EVENT_HEARTBEAT)
            .tag(MetricNames.TAG_CAUSE, MetricNames.CAUSE_ILLEGAL_STATE)
            .counter()
            .count());
  }

  @Test
  void sendFailure_onAnUnexpectedRuntimeException_fallsBackToTheOtherCause() throws Exception {
    CapturingStreamService service = new CapturingStreamService();
    UUID recipientUserId = UUID.randomUUID();
    doThrow(new IllegalArgumentException("odd"))
        .when(service.emitter)
        .send(any(SseEmitter.SseEventBuilder.class));

    service.subscribe(recipientUserId);

    assertEquals(
        1.0,
        service
            .registry
            .get(MetricNames.SSE_SEND_FAILURES)
            .tag(MetricNames.TAG_EVENT, MetricNames.SSE_EVENT_CONNECTED)
            .tag(MetricNames.TAG_CAUSE, MetricNames.CAUSE_OTHER)
            .counter()
            .count());
  }

  @Test
  void subscribe_capEviction_countsAndLogsTheRetirement() {
    DistinctEmitterStreamService service = new DistinctEmitterStreamService();
    UUID sub = UUID.randomUUID();

    List<ILoggingEvent> events =
        withStreamLogAppender(
            () -> {
              for (int i = 0; i < NotificationStreamService.MAX_EMITTERS_PER_SUB + 2; i++) {
                service.subscribe(sub);
              }
            });

    assertEquals(2.0, service.registry.get(MetricNames.SSE_EMITTERS_EVICTED).counter().count());
    ILoggingEvent evictionLine =
        events.stream()
            .filter(e -> e.getFormattedMessage().contains("Evicting oldest SSE emitter"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the cap eviction must leave a log line"));
    assertEquals(Level.DEBUG, evictionLine.getLevel());
    assertTrue(evictionLine.getFormattedMessage().contains(sub.toString()));
    assertTrue(
        evictionLine
            .getFormattedMessage()
            .contains(String.valueOf(NotificationStreamService.MAX_EMITTERS_PER_SUB)),
        "the line must name the cap that was hit: " + evictionLine.getFormattedMessage());
  }

  /**
   * Runs {@code body} with the registry's logger forced to DEBUG and a {@link ListAppender}
   * attached, then restores the previous level and detaches the appender.
   *
   * @param body the action whose log output is wanted
   * @return the captured log events, in order
   */
  private static List<ILoggingEvent> withStreamLogAppender(Runnable body) {
    Logger logger = (Logger) LoggerFactory.getLogger(NotificationStreamService.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      body.run();
      return List.copyOf(appender.list);
    } finally {
      logger.setLevel(previous);
      logger.detachAppender(appender);
    }
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
