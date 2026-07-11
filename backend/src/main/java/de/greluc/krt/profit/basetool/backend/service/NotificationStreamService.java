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

/**
 * In-memory registry of live Server-Sent-Event subscribers, keyed by recipient {@code sub}
 * (REQ-NOTIF-010).
 *
 * <p>This registry delivers to <em>this</em> instance's emitters only; cross-replica fan-out is
 * layered on top by {@link NotificationFanout} (local-first, then Redis pub/sub — ADR-0094,
 * discharging the ADR-0016 follow-up), not by this class. Push is strictly best-effort: a failed
 * send simply drops that emitter, and the frontend's polling (REQ-NOTIF-006) remains the guaranteed
 * fallback. A periodic named {@code heartbeat} event keeps idle connections alive across proxies
 * and doubles as a browser-visible liveness signal so the client can detect a half-open stream (TCP
 * up, stream dead) and fall back to the fast poll (REQ-NOTIF-010, REQ-SEC-012).
 */
@Service
@Slf4j
public class NotificationStreamService {

  /** How long a single SSE connection is held open before the client must reconnect. */
  private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  /**
   * Max concurrent SSE streams retained per recipient {@code sub} (#1156). Every browser tab /
   * device opens its own stream; beyond this many the OLDEST is retired with a terminal {@code
   * replaced} event the client treats as do-not-reconnect, so one user's many tabs cannot multiply
   * against the org-wide relay pool (which was sized on one stream per viewer). Kept small — a
   * handful of tabs / devices is normal; more is almost always stale tabs. Package-private for the
   * test.
   */
  static final int MAX_EMITTERS_PER_SUB = 5;

  /**
   * A {@link Queue} (FIFO) per recipient rather than a bare set, so {@link #MAX_EMITTERS_PER_SUB}
   * eviction can retire the OLDEST stream (poll head) while adds append to the tail. {@link
   * ConcurrentLinkedQueue} keeps {@link #publish}/{@link #heartbeat} iteration weakly-consistent
   * and lock-free while {@link #subscribe}/{@link #remove} mutate the queue atomically under the
   * map entry's bin lock via {@code compute} (#1157).
   */
  private final Map<UUID, Queue<SseEmitter>> emittersBySub = new ConcurrentHashMap<>();

  private final MeterRegistry meterRegistry;

  /**
   * Binds the {@code basetool_sse_connections} gauge to the live emitter registry (REQ-OBS-011) —
   * the total number of open SSE subscriptions across all recipients on this instance, computed on
   * scrape. Unlabelled: recipient {@code sub} is PII / unbounded. Zero here while the frontend
   * still reports {@code basetool_active_sessions} means the push channel is dead ({@code
   * SsePushChannelDead}), e.g. reverse-proxy buffering drift, and clients silently fell back to the
   * unread-count poll.
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
   * @param recipientSub the subscribing caller's {@code sub}
   * @return the registered emitter
   */
  @NotNull
  public SseEmitter subscribe(@NotNull UUID recipientSub) {
    SseEmitter emitter = newEmitter();
    // #1157: register under the map entry's bin lock so an old stream completing concurrently
    // cannot
    // evict the entry after this thread read the queue but before its add lands (which would orphan
    // a
    // live emitter — silently dead for up to EMITTER_TIMEOUT_MS). #1156: cap the streams per user;
    // when full, evict the OLDEST (queue head) — retired outside the lambda below.
    List<SseEmitter> evicted = new ArrayList<>();
    emittersBySub.compute(
        recipientSub,
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
    // Retire evicted emitters OUTSIDE the compute lambda: complete() fires onCompletion ->
    // remove(),
    // which re-enters compute() on the same key — illegal from within a ConcurrentHashMap
    // remapping.
    // They were already polled out, so that remove() is a harmless no-op.
    for (SseEmitter old : evicted) {
      retireReplaced(old);
    }
    emitter.onCompletion(() -> remove(recipientSub, emitter));
    emitter.onTimeout(
        () -> {
          // Complete the emitter on timeout so Spring MVC records a NORMAL async completion rather
          // than raising AsyncRequestTimeoutException — which Micrometer books as a phantom 503 on
          // http.server.requests even though the client received a clean 30-minute stream and
          // simply
          // reconnects. Without the explicit complete() the request finalizes as a server error and
          // inflates the frontend's 5xx rate (REQ-NOTIF-010). Removal also runs via the
          // onCompletion
          // callback complete() triggers; the extra remove() here is idempotent.
          remove(recipientSub, emitter);
          emitter.complete();
        });
    emitter.onError(error -> remove(recipientSub, emitter));
    try {
      emitter.send(SseEmitter.event().name("connected").data("ok"));
    } catch (IOException | RuntimeException e) {
      recordSendFailure(MetricNames.SSE_EVENT_CONNECTED);
      remove(recipientSub, emitter);
    }
    return emitter;
  }

  /**
   * Pushes a lightweight "notification" event to every live subscriber of the given recipients so
   * their client refreshes its unread state. Dead emitters are dropped.
   *
   * @param recipientSubs the recipients whose connections to notify
   */
  public void publish(@NotNull Collection<UUID> recipientSubs) {
    for (UUID recipientSub : recipientSubs) {
      Queue<SseEmitter> emitters = emittersBySub.get(recipientSub);
      if (emitters == null) {
        continue;
      }
      for (SseEmitter emitter : emitters) {
        try {
          emitter.send(SseEmitter.event().name("notification").data("new"));
        } catch (IOException | RuntimeException e) {
          recordSendFailure(MetricNames.SSE_EVENT_NOTIFICATION);
          remove(recipientSub, emitter);
        }
      }
    }
  }

  /**
   * Sends a named {@code heartbeat} event to all live emitters so idle SSE connections survive
   * proxy idle timeouts and the browser gets a periodic liveness signal.
   *
   * <p>It is a named event (carrying a token payload), not an SSE comment, on purpose: browsers'
   * {@code EventSource} swallow comments at the protocol level, so a comment cannot reset a
   * client-side liveness watchdog. A named event lets the client notice a half-open stream (no
   * traffic for several beats) and fall back to the fast unread-count poll (REQ-NOTIF-010,
   * REQ-SEC-012). Dead emitters are dropped on send failure.
   */
  @Scheduled(fixedRateString = "${app.notifications.sse.heartbeat-interval:PT20S}")
  public void heartbeat() {
    emittersBySub.forEach(
        (recipientSub, emitters) ->
            emitters.forEach(
                emitter -> {
                  try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("ok"));
                  } catch (IOException | RuntimeException e) {
                    recordSendFailure(MetricNames.SSE_EVENT_HEARTBEAT);
                    remove(recipientSub, emitter);
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
   * Bumps {@code basetool_sse_send_failures_total} for a push that failed on the named SSE event,
   * just before the dead emitter is dropped. The {@code event} tag is a fixed literal ({@code
   * connected} / {@code notification} / {@code heartbeat}) — never recipient data.
   *
   * @param event the SSE event name whose send failed
   */
  private void recordSendFailure(@NotNull String event) {
    meterRegistry.counter(MetricNames.SSE_SEND_FAILURES, MetricNames.TAG_EVENT, event).increment();
  }

  /**
   * Retires an emitter evicted by the per-user cap (#1156): sends a terminal named {@code replaced}
   * event the client treats as do-not-reconnect, then completes it. Both steps swallow failures —
   * the emitter is being dropped regardless and may already be dead. Called only from {@link
   * #subscribe}, after the evicted emitter was already removed from the queue.
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

  private void remove(@NotNull UUID recipientSub, @NotNull SseEmitter emitter) {
    // #1157: remove-and-maybe-evict atomically under the entry's bin lock, so the empty-check
    // cannot
    // race a concurrent subscribe() into an orphaned queue.
    emittersBySub.compute(
        recipientSub,
        (key, queue) -> {
          if (queue == null) {
            return null;
          }
          queue.remove(emitter);
          return queue.isEmpty() ? null : queue;
        });
  }
}
