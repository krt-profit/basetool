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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory registry of this instance's Server-Sent-Event subscribers, keyed by recipient {@code
 * sub} (REQ-NOTIF-010).
 *
 * <p>Push is best-effort: a failed send drops the emitter. A periodic named {@code heartbeat} event
 * keeps idle connections alive and lets clients detect a half-open stream.
 */
@Service
@Slf4j
public class NotificationStreamService {

  /** How long a single SSE connection is held open before the client must reconnect. */
  private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  /** What the event carried before it carried anything, and what a refresh-only push still is. */
  private static final String REFRESH_ONLY_PAYLOAD = "new";

  /** Renders the signal; stateless and thread-safe, so one instance serves every push. */
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  /**
   * Maximum concurrent SSE streams per recipient {@code sub}; beyond it the oldest is retired with
   * a terminal {@code replaced} event.
   */
  static final int MAX_EMITTERS_PER_SUB = 5;

  /**
   * FIFO emitter queue per recipient, so eviction retires the oldest stream; mutated atomically via
   * {@code compute}.
   */
  private final Map<UUID, Queue<SseEmitter>> emittersBySub = new ConcurrentHashMap<>();

  private final MeterRegistry meterRegistry;

  /**
   * Binds the unlabelled {@code basetool_sse_connections} gauge to the number of open SSE
   * subscriptions on this instance (REQ-OBS-011).
   *
   * @param meterRegistry the Micrometer registry the SSE gauge and send-failure counter bind to
   */
  public NotificationStreamService(@NotNull MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    Gauge.builder(
            MetricNames.SSE_CONNECTIONS,
            emittersBySub,
            map -> map.values().stream().mapToInt(Queue::size).sum())
        .description("Live SSE subscriber connections summed across all recipients.")
        .register(meterRegistry);
  }

  /**
   * Registers a new SSE subscription for a recipient and returns its emitter. The emitter
   * de-registers itself on completion, timeout or error.
   *
   * @param recipientUserId the subscribing caller's {@code sub}
   * @return the registered emitter
   */
  @NotNull
  public SseEmitter subscribe(@NotNull UUID recipientUserId) {
    SseEmitter emitter = newEmitter();
    List<SseEmitter> evicted = new ArrayList<>();
    emittersBySub.compute(
        recipientUserId,
        (key, queue) -> {
          Queue<SseEmitter> q = (queue != null) ? queue : new ConcurrentLinkedQueue<>();
          while (q.size() >= MAX_EMITTERS_PER_SUB) {
            SseEmitter oldest = q.poll();
            if (oldest == null) {
              break;
            }
            evicted.add(oldest);
          }
          q.add(emitter);
          return q;
        });
    for (SseEmitter old : evicted) {
      meterRegistry.counter(MetricNames.SSE_EMITTERS_EVICTED).increment();
      log.debug(
          "Evicting oldest SSE emitter for recipient {}: per-recipient cap {} reached",
          recipientUserId,
          MAX_EMITTERS_PER_SUB);
      retireReplaced(old);
    }
    emitter.onCompletion(() -> remove(recipientUserId, emitter));
    emitter.onTimeout(
        () -> {
          remove(recipientUserId, emitter);
          emitter.complete();
        });
    emitter.onError(error -> remove(recipientUserId, emitter));
    try {
      emitter.send(SseEmitter.event().name("connected").data("ok"));
    } catch (IOException | RuntimeException e) {
      recordSendFailure(MetricNames.SSE_EVENT_CONNECTED, recipientUserId, e);
      remove(recipientUserId, emitter);
    }
    return emitter;
  }

  /**
   * Pushes a {@code notification} event carrying the serialized signal to every live subscriber of
   * the given recipients; dead emitters are dropped.
   *
   * @param recipientUserIds the recipients whose connections to notify
   * @param signal what those recipients are being told
   */
  public void publish(
      @NotNull Collection<UUID> recipientUserIds, @NotNull NotificationSignal signal) {
    String payload = serialize(signal);
    for (UUID recipientUserId : recipientUserIds) {
      Queue<SseEmitter> emitters = emittersBySub.get(recipientUserId);
      if (emitters == null) {
        continue;
      }
      for (SseEmitter emitter : emitters) {
        try {
          emitter.send(SseEmitter.event().name("notification").data(payload));
        } catch (IOException | RuntimeException e) {
          recordSendFailure(MetricNames.SSE_EVENT_NOTIFICATION, recipientUserId, e);
          remove(recipientUserId, emitter);
        }
      }
    }
  }

  /**
   * Renders a signal as the event's data; a refresh-only signal and a serialization failure both
   * render as {@code "new"}.
   *
   * @param signal what to render
   * @return the event data
   */
  @NotNull
  private String serialize(@NotNull NotificationSignal signal) {
    if (!signal.describesNotification()) {
      return REFRESH_ONLY_PAYLOAD;
    }
    try {
      ObjectNode root = JSON_MAPPER.createObjectNode();
      root.put("type", String.valueOf(signal.type()));
      root.put("entityType", signal.entityType());
      root.put("entityId", signal.entityId() == null ? null : signal.entityId().toString());
      ObjectNode params = root.putObject("params");
      signal.params().forEach(params::put);
      return JSON_MAPPER.writeValueAsString(root);
    } catch (RuntimeException e) {
      log.debug("Notification signal could not be serialised; falling back to the bare push", e);
      return REFRESH_ONLY_PAYLOAD;
    }
  }

  /**
   * Sends a named {@code heartbeat} event to all live emitters, keeping idle connections open and
   * giving clients a liveness signal (REQ-NOTIF-010); dead emitters are dropped.
   */
  @Scheduled(fixedRateString = "${app.notifications.sse.heartbeat-interval:PT20S}")
  public void heartbeat() {
    emittersBySub.forEach(
        (recipientUserId, emitters) ->
            emitters.forEach(
                emitter -> {
                  try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("ok"));
                  } catch (IOException | RuntimeException e) {
                    recordSendFailure(MetricNames.SSE_EVENT_HEARTBEAT, recipientUserId, e);
                    remove(recipientUserId, emitter);
                  }
                }));
  }

  /**
   * Creates the {@link SseEmitter} backing a new subscription, with the registry's connection
   * timeout. Extracted as a seam so tests can substitute a mock emitter and assert on the events
   * the registry sends (connected / heartbeat / notification).
   *
   * @return a fresh emitter holding the connection open for {@link #EMITTER_TIMEOUT_MS}
   */
  @NotNull
  protected SseEmitter newEmitter() {
    return new SseEmitter(EMITTER_TIMEOUT_MS);
  }

  /**
   * Bumps {@code basetool_sse_send_failures_total} for a failed push and logs the cause at DEBUG.
   *
   * @param event the SSE event name whose send failed
   * @param recipientUserId the {@code sub} of the recipient whose emitter died
   * @param cause the exception the emitter write threw
   */
  private void recordSendFailure(
      @NotNull String event, @NotNull UUID recipientUserId, @NotNull Throwable cause) {
    meterRegistry
        .counter(
            MetricNames.SSE_SEND_FAILURES,
            MetricNames.TAG_EVENT,
            event,
            MetricNames.TAG_CAUSE,
            SseSendFailureCause.tagOf(cause))
        .increment();
    log.debug(
        "Dropping SSE emitter of recipient {} after a failed '{}' push",
        recipientUserId,
        event,
        cause);
  }

  /**
   * Retires an emitter evicted by the per-user cap: sends a terminal {@code replaced} event, then
   * completes it, swallowing failures.
   *
   * @param emitter the evicted (oldest) emitter to retire
   */
  private void retireReplaced(@NotNull SseEmitter emitter) {
    try {
      emitter.send(SseEmitter.event().name("replaced").data("ok"));
    } catch (IOException | RuntimeException e) {
      log.debug("Evicted SSE emitter already dead before 'replaced' event", e);
    }
    try {
      emitter.complete();
    } catch (RuntimeException e) {
      log.debug("Evicted SSE emitter completion raced its own teardown", e);
    }
  }

  private void remove(@NotNull UUID recipientUserId, @NotNull SseEmitter emitter) {
    emittersBySub.compute(
        recipientUserId,
        (key, queue) -> {
          if (queue == null) {
            return null;
          }
          queue.remove(emitter);
          return queue.isEmpty() ? null : queue;
        });
  }
}
