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

package de.greluc.krt.profit.basetool.frontend.service;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * In-memory editor-presence store for live-sync topics that carry presence dots (REQ-FE-015,
 * ADR-0094) — today only the mission surface.
 *
 * <p>Tracks, per topic and per section key, which users are currently editing that section. Entries
 * decay after {@link #ENTRY_TTL} since the last heartbeat — a client that closes its tab or
 * navigates away without sending a {@code blur} message is therefore reaped within the TTL window
 * by the scheduled cleanup in {@code LiveSyncWebSocketHandler}.
 *
 * <p><b>Per-instance only.</b> The state lives in a {@link ConcurrentHashMap} local to this JVM.
 * Unlike the {@code changed} relay — which fans out across replicas via Redis pub/sub (ADR-0094) —
 * presence dots are deliberately <em>not</em> mirrored across instances: they are a best-effort
 * awareness cue, and cross-replica dots would need shared TTL state or presence-frame mirroring for
 * a cosmetic feature. Consequence: viewers on different replicas may see different dot sets. This
 * class stays the single swap-out point if that follow-up is ever taken.
 *
 * <p>Awareness, not locking: this service only <em>describes</em> who is editing where. It never
 * blocks a write or rejects a save. The optimistic-lock counters remain the single source of truth
 * for conflict resolution; this is just a UX-layer hint so two users notice the overlap before they
 * collide on a 409.
 */
@Service
@Slf4j
public class LiveSyncPresenceService {

  /**
   * Time after the last heartbeat at which a presence entry is considered stale and removed.
   * Heartbeats arrive every ~60s from the client (see {@code HEARTBEAT_MS} in {@code
   * krt-live-sync.js}); 120s gives two missed beats of slack before the indicator disappears.
   *
   * <p>Tune this together with the client-side heartbeat: the 120s / 60s pairing keeps a
   * two-missed-beats safety ratio. Lowering this in isolation would reap editors that are still
   * actively heartbeating.
   */
  public static final Duration ENTRY_TTL = Duration.ofSeconds(120);

  /**
   * Upper bound on the number of distinct section keys tracked per topic. A detail page exposes
   * roughly a dozen editable panels, so this cap sits well above legitimate use while bounding the
   * per-topic presence-map memory a crafted client could otherwise grow by looping {@code focus}
   * frames with unique section keys. The WebSocket handler's per-session token bucket bounds the
   * growth <em>rate</em>; this bounds the absolute <em>size</em>. Package-private for the test.
   */
  static final int MAX_SECTIONS_PER_TOPIC = 64;

  private final Map<String, Map<String, Map<String, Entry>>> byTopic = new ConcurrentHashMap<>();

  /**
   * Binds the {@code basetool_mission_presence_missions} gauge to the live presence map
   * (REQ-OBS-011) — the count of topics currently tracked with at least one live editor in this
   * instance. The gauge is unlabelled and its name is legacy-pinned (presence is mission-only at
   * ship time, so the value is unchanged and the dashboard panel keeps meaning); topic id, section
   * key and user id are all unbounded and PII-adjacent, so none is used as a tag. Per-instance
   * edit-awareness (see the class note), not a global online-user roster; the closest online-user
   * proxy is {@code basetool_active_sessions}.
   *
   * @param meterRegistry the Micrometer registry the presence gauge is bound to
   */
  public LiveSyncPresenceService(@NotNull MeterRegistry meterRegistry) {
    Gauge.builder(MetricNames.MISSION_PRESENCE_MISSIONS, byTopic, Map::size)
        .description("Live-sync topics with at least one live editor tracked in this instance.")
        .register(meterRegistry);
  }

  /**
   * Record an editor's heartbeat (or initial focus) on a section of a topic. Replaces any previous
   * entry for the same {@code (topic, sectionKey, userId)} triple so the heartbeat timestamp moves
   * forward.
   *
   * @param topic canonical topic this presence belongs to
   * @param sectionKey panel key (e.g. {@code "crew"}, {@code "overview"})
   * @param userId stable identifier of the editing user (JWT {@code sub} via OIDC)
   * @param displayName name to show in the UI (already redacted for guests by the caller)
   * @return {@code true} if this is a new editor for that section (the caller may want to broadcast
   *     a state update only then; in practice we broadcast on every change anyway)
   */
  public boolean touch(
      @NotNull String topic,
      @NotNull String sectionKey,
      @NotNull String userId,
      @NotNull String displayName) {
    Map<String, Map<String, Entry>> sections =
        byTopic.computeIfAbsent(topic, ignored -> new ConcurrentHashMap<>());
    Map<String, Entry> editors = sections.get(sectionKey);
    if (editors == null) {
      // Refuse a first-seen section once the topic is already at the distinct-section cap, rather
      // than growing the map. Guards against a crafted client looping focus frames with unique
      // section keys to exhaust memory (the handler additionally rate-limits and length-caps the
      // key). A concurrent pair of first-sightings may overshoot the cap by a small constant, which
      // is harmless — the bound is a memory ceiling, not an exact count.
      if (sections.size() >= MAX_SECTIONS_PER_TOPIC) {
        return false;
      }
      editors = sections.computeIfAbsent(sectionKey, ignored -> new ConcurrentHashMap<>());
    }
    Instant now = Instant.now();
    Entry prev = editors.put(userId, new Entry(userId, displayName, now));
    return prev == null;
  }

  /**
   * Drop the explicit presence entry for {@code (topic, sectionKey, userId)} — invoked on blur, on
   * socket close, or when the user submits a save. Idempotent.
   *
   * @param topic canonical topic the entry belongs to
   * @param sectionKey section key
   * @param userId user id
   * @return {@code true} if an entry was actually removed
   */
  public boolean clear(@NotNull String topic, @NotNull String sectionKey, @NotNull String userId) {
    Map<String, Map<String, Entry>> sections = byTopic.get(topic);
    if (sections == null) {
      return false;
    }
    Map<String, Entry> editors = sections.get(sectionKey);
    if (editors == null) {
      return false;
    }
    boolean removed = editors.remove(userId) != null;
    if (editors.isEmpty()) {
      sections.remove(sectionKey, editors);
    }
    if (sections.isEmpty()) {
      byTopic.remove(topic, sections);
    }
    return removed;
  }

  /**
   * Drop every presence entry for {@code userId} on {@code topic} across all sections — invoked
   * when the user's last session on the topic closes. Idempotent.
   *
   * @param topic canonical topic
   * @param userId user id
   * @return list of section keys from which the user was actually removed
   */
  public List<String> clearAll(@NotNull String topic, @NotNull String userId) {
    Map<String, Map<String, Entry>> sections = byTopic.get(topic);
    if (sections == null) {
      return List.of();
    }
    List<String> affected = new ArrayList<>();
    for (Map.Entry<String, Map<String, Entry>> sectionEntry : sections.entrySet()) {
      if (sectionEntry.getValue().remove(userId) != null) {
        affected.add(sectionEntry.getKey());
      }
    }
    sections.entrySet().removeIf(e -> e.getValue().isEmpty());
    if (sections.isEmpty()) {
      byTopic.remove(topic, sections);
    }
    return affected;
  }

  /**
   * Reap entries older than {@link #ENTRY_TTL}. Called by the WebSocket handler's scheduled task;
   * returns the set of {@code (topic, sectionKey)} pairs that lost at least one entry so the
   * handler can decide whether to broadcast updated state to those rooms.
   *
   * @param now reference instant — pass {@link Instant#now()} in production; tests pass a frozen
   *     value
   * @return list of affected topic/section pairs (empty if nothing expired)
   */
  public List<TopicSectionRef> reapExpired(@NotNull Instant now) {
    Instant cutoff = now.minus(ENTRY_TTL);
    List<TopicSectionRef> affected = new ArrayList<>();
    for (Map.Entry<String, Map<String, Map<String, Entry>>> topicEntry : byTopic.entrySet()) {
      String topic = topicEntry.getKey();
      Map<String, Map<String, Entry>> sections = topicEntry.getValue();
      for (Map.Entry<String, Map<String, Entry>> sectionEntry : sections.entrySet()) {
        Map<String, Entry> editors = sectionEntry.getValue();
        boolean changed = editors.values().removeIf(e -> e.lastHeartbeat().isBefore(cutoff));
        if (changed) {
          affected.add(new TopicSectionRef(topic, sectionEntry.getKey()));
        }
      }
      sections.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    byTopic.entrySet().removeIf(e -> e.getValue().isEmpty());
    return affected;
  }

  /**
   * Snapshot of the current presence state for one topic, keyed by section. Returns an immutable
   * view; modification of the returned map throws.
   *
   * @param topic canonical topic
   * @param now reference instant for filtering out entries that would expire on the next reap
   * @return map from section key to the list of editors currently active on that section
   */
  public Map<String, List<Entry>> snapshot(@NotNull String topic, @NotNull Instant now) {
    Map<String, Map<String, Entry>> sections = byTopic.get(topic);
    if (sections == null) {
      return Map.of();
    }
    Instant cutoff = now.minus(ENTRY_TTL);
    Map<String, List<Entry>> result = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Entry>> sectionEntry : sections.entrySet()) {
      List<Entry> live = new ArrayList<>();
      for (Entry e : sectionEntry.getValue().values()) {
        if (!e.lastHeartbeat().isBefore(cutoff)) {
          live.add(e);
        }
      }
      if (!live.isEmpty()) {
        result.put(sectionEntry.getKey(), Collections.unmodifiableList(live));
      }
    }
    return Collections.unmodifiableMap(result);
  }

  /**
   * Returns the list of topics currently tracked. Used by the scheduled cleanup loop to know which
   * rooms to broadcast state into after a reap.
   *
   * @return immutable snapshot of topics with at least one tracked editor
   */
  public List<String> trackedTopics() {
    return List.copyOf(byTopic.keySet());
  }

  /**
   * Returns the {@link Entry} for {@code userId} on {@code (topic, sectionKey)}, or {@code null}.
   * Intended for tests.
   *
   * @param topic canonical topic
   * @param sectionKey section key
   * @param userId user id
   * @return the entry, or {@code null} if absent
   */
  @Nullable
  public Entry get(@NotNull String topic, @NotNull String sectionKey, @NotNull String userId) {
    Map<String, Map<String, Entry>> sections = byTopic.get(topic);
    if (sections == null) {
      return null;
    }
    Map<String, Entry> editors = sections.get(sectionKey);
    if (editors == null) {
      return null;
    }
    return editors.get(userId);
  }

  /**
   * One presence entry: which user is editing, what to call them in the UI, and when their last
   * heartbeat arrived.
   *
   * @param userId stable identifier of the editing user
   * @param displayName name to show in the UI (already redacted for guests by the caller)
   * @param lastHeartbeat instant of the most recent heartbeat or focus message
   */
  public record Entry(String userId, String displayName, Instant lastHeartbeat) {}

  /**
   * Lightweight key pair referencing a single section of a single topic. Used to communicate which
   * rooms changed after a reap.
   *
   * @param topic canonical topic
   * @param sectionKey section key
   */
  public record TopicSectionRef(String topic, String sectionKey) {}
}
