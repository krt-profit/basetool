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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * In-memory presence store for the mission detail page (Stufe 3 / awareness).
 *
 * <p>Tracks, per mission and per section key, which users are currently editing that section.
 * Entries decay after {@link #ENTRY_TTL} since the last heartbeat — a client that closes its tab or
 * navigates away without sending a {@code blur} message is therefore reaped within the TTL window
 * by the scheduled cleanup in {@code MissionPresenceWebSocketHandler}.
 *
 * <p><b>Single-instance only.</b> The state lives in a {@link ConcurrentHashMap} local to this JVM.
 * If the frontend is ever scaled out to multiple replicas, this needs to move behind a Redis
 * pub/sub fan-out (the project already runs Redis for Spring Session, so the dependency is in
 * place). The minimal swap-out point is this class: every call site already goes through it.
 *
 * <p>Awareness, not locking: this service only <em>describes</em> who is editing where. It never
 * blocks a write or rejects a save. The optimistic-lock counters introduced in Stufe 1 remain the
 * single source of truth for conflict resolution; this is just a UX-layer hint so two users notice
 * the overlap before they collide on a 409.
 */
@Service
@Slf4j
public class MissionPresenceService {

  /**
   * Time after the last heartbeat at which a presence entry is considered stale and removed.
   * Heartbeats arrive every ~60s from the client (see {@code HEARTBEAT_MS} in {@code
   * mission-presence.js}); 120s gives two missed beats of slack before the indicator disappears.
   *
   * <p>L-7 from the performance audit raised both this value and the client-side heartbeat from 30s
   * / 10s to keep the two-missed-beats safety ratio. Per-editor WebSocket frame traffic drops by 6×
   * as a result; the UX cost is that a peer sees "user stopped editing" up to ~120s after the
   * editor navigates away (was ~30s before). Tune both values together — lowering this in isolation
   * would reap editors that are still actively heartbeating.
   */
  public static final Duration ENTRY_TTL = Duration.ofSeconds(120);

  private final Map<UUID, Map<String, Map<String, Entry>>> byMission = new ConcurrentHashMap<>();

  /**
   * Binds the {@code basetool_mission_presence_missions} gauge to the live presence map
   * (REQ-OBS-011) — the count of missions currently tracked with at least one live editor in this
   * instance. The gauge is unlabelled: mission id, section key and user id are all unbounded and
   * PII-adjacent, so none is used as a tag. Single-JVM edit-awareness (see the class note), not a
   * global online-user roster; the closest online-user proxy is {@code basetool_active_sessions}.
   *
   * @param meterRegistry the Micrometer registry the presence gauge is bound to
   */
  public MissionPresenceService(@NotNull MeterRegistry meterRegistry) {
    Gauge.builder(MetricNames.MISSION_PRESENCE_MISSIONS, byMission, Map::size)
        .description("Missions with at least one live editor tracked in this frontend instance.")
        .register(meterRegistry);
  }

  /**
   * Record an editor's heartbeat (or initial focus) on a section of a mission. Replaces any
   * previous entry for the same {@code (missionId, sectionKey, userId)} triple so the heartbeat
   * timestamp moves forward.
   *
   * @param missionId mission this presence belongs to
   * @param sectionKey panel key (e.g. {@code "details"}, {@code "participants"})
   * @param userId stable identifier of the editing user (JWT {@code sub} via OIDC)
   * @param displayName name to show in the UI (already redacted for guests by the caller)
   * @return {@code true} if this is a new editor for that section (the caller may want to broadcast
   *     a state update only then; in practice we broadcast on every change anyway)
   */
  public boolean touch(
      @NotNull UUID missionId,
      @NotNull String sectionKey,
      @NotNull String userId,
      @NotNull String displayName) {
    Map<String, Map<String, Entry>> sections =
        byMission.computeIfAbsent(missionId, ignored -> new ConcurrentHashMap<>());
    Map<String, Entry> editors =
        sections.computeIfAbsent(sectionKey, ignored -> new ConcurrentHashMap<>());
    Instant now = Instant.now();
    Entry prev = editors.put(userId, new Entry(userId, displayName, now));
    return prev == null;
  }

  /**
   * Drop the explicit presence entry for {@code (missionId, sectionKey, userId)} — invoked on blur,
   * on socket close, or when the user submits a save. Idempotent.
   *
   * @return {@code true} if an entry was actually removed
   */
  public boolean clear(
      @NotNull UUID missionId, @NotNull String sectionKey, @NotNull String userId) {
    Map<String, Map<String, Entry>> sections = byMission.get(missionId);
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
      byMission.remove(missionId, sections);
    }
    return removed;
  }

  /**
   * Drop every presence entry for {@code userId} on {@code missionId} across all sections — invoked
   * when the user's WebSocket closes. Idempotent.
   *
   * @return list of section keys from which the user was actually removed
   */
  public List<String> clearAll(@NotNull UUID missionId, @NotNull String userId) {
    Map<String, Map<String, Entry>> sections = byMission.get(missionId);
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
      byMission.remove(missionId, sections);
    }
    return affected;
  }

  /**
   * Reap entries older than {@link #ENTRY_TTL}. Called by the WebSocket handler's scheduled task;
   * returns the set of {@code (missionId, sectionKey)} pairs that lost at least one entry so the
   * handler can decide whether to broadcast updated state to those rooms.
   *
   * @param now reference instant — pass {@link Instant#now()} in production; tests pass a frozen
   *     value
   * @return list of affected mission/section pairs (empty if nothing expired)
   */
  public List<MissionSectionRef> reapExpired(@NotNull Instant now) {
    Instant cutoff = now.minus(ENTRY_TTL);
    List<MissionSectionRef> affected = new ArrayList<>();
    for (Map.Entry<UUID, Map<String, Map<String, Entry>>> missionEntry : byMission.entrySet()) {
      UUID missionId = missionEntry.getKey();
      Map<String, Map<String, Entry>> sections = missionEntry.getValue();
      for (Map.Entry<String, Map<String, Entry>> sectionEntry : sections.entrySet()) {
        Map<String, Entry> editors = sectionEntry.getValue();
        boolean changed = editors.values().removeIf(e -> e.lastHeartbeat().isBefore(cutoff));
        if (changed) {
          affected.add(new MissionSectionRef(missionId, sectionEntry.getKey()));
        }
      }
      sections.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    byMission.entrySet().removeIf(e -> e.getValue().isEmpty());
    return affected;
  }

  /**
   * Snapshot of the current presence state for one mission, keyed by section. Returns an immutable
   * view; modification of the returned map throws.
   *
   * @param missionId mission id
   * @param now reference instant for filtering out entries that would expire on the next reap
   * @return map from section key to the list of editors currently active on that section
   */
  public Map<String, List<Entry>> snapshot(@NotNull UUID missionId, @NotNull Instant now) {
    Map<String, Map<String, Entry>> sections = byMission.get(missionId);
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
   * Returns the list of mission ids currently tracked. Used by the scheduled cleanup loop to know
   * which rooms to broadcast state into after a reap.
   *
   * @return immutable snapshot of mission ids with at least one tracked editor
   */
  public List<UUID> trackedMissions() {
    return List.copyOf(byMission.keySet());
  }

  /**
   * Returns the {@link Entry} for {@code userId} on {@code (missionId, sectionKey)}, or {@code
   * null}. Intended for tests.
   *
   * @param missionId mission id
   * @param sectionKey section key
   * @param userId user id
   * @return the entry, or {@code null} if absent
   */
  @Nullable
  public Entry get(@NotNull UUID missionId, @NotNull String sectionKey, @NotNull String userId) {
    Map<String, Map<String, Entry>> sections = byMission.get(missionId);
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
   * Lightweight key pair referencing a single section of a single mission. Used to communicate
   * which rooms changed after a reap.
   *
   * @param missionId mission id
   * @param sectionKey section key
   */
  public record MissionSectionRef(UUID missionId, String sectionKey) {}
}
