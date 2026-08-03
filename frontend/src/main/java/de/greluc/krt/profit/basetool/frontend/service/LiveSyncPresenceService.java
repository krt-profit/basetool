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
 * <p><b>Two partitions, one merged view</b> (ADR-0126, #1237). {@code byTopic} holds the editors
 * whose sockets live on <em>this</em> JVM; {@code remoteByTopic} holds the snapshots peer replicas
 * gossip over Redis, keyed by the publishing instance id. {@link #snapshot(String, Instant)} merges
 * both, so a viewer sees every editor of a topic regardless of which replica served them. The two
 * partitions never mix: only local entries decay on {@link #ENTRY_TTL} heartbeats, are published to
 * peers, or are touched by {@code focus}/{@code blur}; a remote partition is replaced wholesale by
 * its origin's next gossip and expires on {@link #REMOTE_PARTITION_TTL} if that origin goes away.
 * Losing Redis therefore degrades presence to exactly the pre-#1237 per-instance behaviour — the
 * local half keeps working untouched.
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

  /**
   * Age past which a mirrored peer-replica partition is dropped (ADR-0126). Every instance
   * re-gossips each of its tracked presence topics on the WebSocket handler's 10 s reaper tick, so
   * this allows two missed gossips before a silent replica's dots disappear — the same
   * two-missed-beats ratio {@link #ENTRY_TTL} keeps against the client heartbeat.
   *
   * <p>Deliberately far below {@link #ENTRY_TTL}: a remote partition's freshness rides the origin's
   * 10 s gossip, not the editor's 60 s heartbeat, so a replica that dies mid-edit stops showing
   * dots within 30 s instead of the two minutes its own local entries would have survived. Raising
   * this without raising the gossip cadence only makes a dead replica's dots linger.
   */
  public static final Duration REMOTE_PARTITION_TTL = Duration.ofSeconds(30);

  /**
   * Upper bound on the number of distinct peer replicas whose presence partitions are held for one
   * topic. The frontend is deployed as a handful of replicas at most, so this sits far above any
   * legitimate topology while bounding the memory a spoofed or misconfigured publisher could grow
   * by gossiping under ever-new instance ids. A partition from an unknown origin is refused once
   * the cap is reached rather than evicting an established one. Package-private for the test.
   */
  static final int MAX_REMOTE_ORIGINS_PER_TOPIC = 16;

  /**
   * Upper bound on the editors kept per section of a mirrored partition. A section with more
   * concurrent editors than this is already unreadable as a dot row, so truncating bounds the
   * consume-side memory without costing anything a viewer could perceive. Package-private for the
   * test.
   */
  static final int MAX_EDITORS_PER_REMOTE_SECTION = 32;

  private final Map<String, Map<String, Map<String, Entry>>> byTopic = new ConcurrentHashMap<>();

  /**
   * Mirrored peer-replica presence: topic → publishing instance id → that instance's last gossiped
   * snapshot (ADR-0126). Held separately from {@link #byTopic} so the local half stays
   * authoritative and untouched — a remote partition is never heartbeat-decayed, blurred or reaped
   * entry by entry, only replaced wholesale or expired as a unit.
   */
  private final Map<String, Map<String, RemotePartition>> remoteByTopic = new ConcurrentHashMap<>();

  /**
   * Binds the {@code basetool_mission_presence_missions} gauge to the live presence map
   * (REQ-OBS-011) — the count of topics currently tracked with at least one live editor in this
   * instance. The gauge is unlabelled and its name is legacy-pinned (presence is mission-only at
   * ship time, so the value is unchanged and the dashboard panel keeps meaning); topic id, section
   * key and user id are all unbounded and PII-adjacent, so none is used as a tag. Per-instance
   * edit-awareness (see the class note), not a global online-user roster; the closest online-user
   * proxy is {@code basetool_active_sessions}.
   *
   * <p>Also binds {@code basetool_livesync_presence_remote_partitions} (REQ-OBS-011): the number of
   * live {@code (topic, peer instance)} partitions mirrored from other replicas. It is the direct
   * "is cross-instance presence actually arriving" signal — on a single-replica deployment it reads
   * a flat zero, and on a multi-replica one a zero while editors are active means the presence
   * gossip is not landing (ADR-0126). Unlabelled for the same reason as the gauge above: topic id
   * and instance id are unbounded.
   *
   * @param meterRegistry the Micrometer registry the presence gauges are bound to
   */
  public LiveSyncPresenceService(@NotNull MeterRegistry meterRegistry) {
    Gauge.builder(MetricNames.MISSION_PRESENCE_MISSIONS, byTopic, Map::size)
        .description("Live-sync topics with at least one live editor tracked in this instance.")
        .register(meterRegistry);
    Gauge.builder(
            MetricNames.LIVESYNC_PRESENCE_REMOTE_PARTITIONS,
            this,
            LiveSyncPresenceService::remotePartitionCount)
        .description("Peer-replica editor-presence partitions currently mirrored on this instance.")
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
   * Merged snapshot of the current presence state for one topic, keyed by section — this instance's
   * own editors plus every non-expired partition peer replicas have gossiped for the topic
   * (ADR-0126). This is what the WebSocket handler serialises into a {@code presence} frame, so a
   * viewer sees the same dots no matter which replica served their page. Returns an immutable view;
   * modification of the returned map throws.
   *
   * <p>A user present in both halves — two tabs load-balanced onto different replicas — is
   * collapsed to a single editor per section, keeping the dot count a count of <em>people</em>
   * rather than of sockets.
   *
   * <p>Remote entries carry the arrival instant of their partition as {@link
   * Entry#lastHeartbeat()}, not the peer's own clock reading: a mirrored partition's liveness is
   * decided by {@link #REMOTE_PARTITION_TTL} against local time here, so nothing in this class ever
   * depends on two hosts' clocks agreeing.
   *
   * @param topic canonical topic
   * @param now reference instant for filtering out entries that would expire on the next reap
   * @return map from section key to the list of editors currently active on that section
   */
  public Map<String, List<Entry>> snapshot(@NotNull String topic, @NotNull Instant now) {
    Map<String, List<Entry>> result = liveLocalEntries(topic, now);
    mergeRemoteEntries(topic, now, result);
    if (result.isEmpty()) {
      return Map.of();
    }
    result.replaceAll((section, editors) -> Collections.unmodifiableList(editors));
    return Collections.unmodifiableMap(result);
  }

  /**
   * This instance's own half of {@link #snapshot(String, Instant)}, reduced to the wire shape
   * gossiped to peer replicas — no heartbeat timestamps (a peer judges freshness by arrival, see
   * {@link #REMOTE_PARTITION_TTL}) and no mirrored entries (that would echo a peer's state back at
   * it and let two instances keep each other's stale dots alive forever).
   *
   * <p>An empty result is meaningful, not a no-op: it is how an instance tells its peers "nobody is
   * editing this topic here any more" after the last local editor blurred, closed their tab or
   * decayed, so the corresponding remote partition is dropped immediately instead of lingering for
   * a full {@link #REMOTE_PARTITION_TTL}.
   *
   * @param topic canonical topic
   * @param now reference instant for filtering out entries that would expire on the next reap
   * @return map from section key to this instance's editors on that section (possibly empty)
   */
  @NotNull
  public Map<String, List<PresenceEditor>> localSnapshot(
      @NotNull String topic, @NotNull Instant now) {
    Map<String, List<Entry>> local = liveLocalEntries(topic, now);
    Map<String, List<PresenceEditor>> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<Entry>> sectionEntry : local.entrySet()) {
      List<PresenceEditor> editors = new ArrayList<>();
      for (Entry e : sectionEntry.getValue()) {
        editors.add(new PresenceEditor(e.userId(), e.displayName()));
      }
      result.put(sectionEntry.getKey(), editors);
    }
    return result;
  }

  /**
   * Replaces the presence partition a peer replica holds for one topic with its freshly gossiped
   * state, or drops it when the peer reports an empty snapshot (ADR-0126). Wholesale replacement —
   * never a per-entry merge — is what makes the mirror self-healing: a lost gossip message costs at
   * most one 10 s tick of staleness and the next message restores the truth, with no delete frames
   * or ordering assumptions.
   *
   * @param topic canonical topic the partition belongs to
   * @param originId stable id of the publishing instance
   * @param sections that instance's editors per section — already length- and whitelist-sanitised
   *     by the caller; an empty map removes the partition
   * @param now arrival instant, used as the partition's freshness reference
   * @return {@code true} if the merged view for {@code topic} actually changed, so the caller
   *     should broadcast a fresh snapshot — {@code false} for the common case of a periodic gossip
   *     that restates what this instance already holds, which must not spam the room
   */
  public boolean applyRemote(
      @NotNull String topic,
      @NotNull String originId,
      @NotNull Map<String, List<PresenceEditor>> sections,
      @NotNull Instant now) {
    if (sections.isEmpty()) {
      Map<String, RemotePartition> origins = remoteByTopic.get(topic);
      if (origins == null) {
        return false;
      }
      boolean removed = origins.remove(originId) != null;
      if (origins.isEmpty()) {
        remoteByTopic.remove(topic, origins);
      }
      return removed;
    }
    Map<String, List<PresenceEditor>> bounded = boundedPartition(sections);
    Map<String, RemotePartition> origins =
        remoteByTopic.computeIfAbsent(topic, ignored -> new ConcurrentHashMap<>());
    RemotePartition previous = origins.get(originId);
    if (previous == null && origins.size() >= MAX_REMOTE_ORIGINS_PER_TOPIC) {
      // Refuse a first-seen origin at the cap rather than growing the map or evicting an
      // established peer. The frontend runs a handful of replicas, so reaching this means a
      // spoofed or misconfigured publisher, and the established partitions are the trustworthy
      // ones.
      log.debug(
          "Refusing mirrored presence partition for topic {}: origin cap {} reached",
          topic,
          MAX_REMOTE_ORIGINS_PER_TOPIC);
      return false;
    }
    origins.put(originId, new RemotePartition(bounded, now));
    return previous == null || !previous.sections().equals(bounded);
  }

  /**
   * Drops mirrored partitions that have not been re-gossiped within {@link #REMOTE_PARTITION_TTL} —
   * the mechanism by which a replica that crashed, was scaled down or lost its Redis connection
   * stops showing phantom dots on every other replica. Called from the WebSocket handler's
   * scheduled tick alongside {@link #reapExpired(Instant)}.
   *
   * @param now reference instant — pass {@link Instant#now()} in production; tests pass a frozen
   *     value
   * @return the canonical topics that lost at least one partition, so the caller can broadcast
   *     their shrunken snapshots (empty if nothing expired)
   */
  @NotNull
  public List<String> reapExpiredRemote(@NotNull Instant now) {
    Instant cutoff = now.minus(REMOTE_PARTITION_TTL);
    List<String> affected = new ArrayList<>();
    for (Map.Entry<String, Map<String, RemotePartition>> topicEntry : remoteByTopic.entrySet()) {
      Map<String, RemotePartition> origins = topicEntry.getValue();
      if (origins.values().removeIf(partition -> partition.receivedAt().isBefore(cutoff))) {
        affected.add(topicEntry.getKey());
      }
    }
    remoteByTopic.entrySet().removeIf(e -> e.getValue().isEmpty());
    return affected;
  }

  /**
   * The number of {@code (topic, peer instance)} partitions currently mirrored from other replicas
   * — backs the {@code basetool_livesync_presence_remote_partitions} gauge and the mirror tests.
   *
   * @return the total partition count across all topics
   */
  public int remotePartitionCount() {
    int total = 0;
    for (Map<String, RemotePartition> origins : remoteByTopic.values()) {
      total += origins.size();
    }
    return total;
  }

  /**
   * Collects this instance's non-expired entries for a topic into a mutable per-section map, the
   * shared basis of {@link #snapshot(String, Instant)} and {@link #localSnapshot(String, Instant)}.
   *
   * @param topic canonical topic
   * @param now reference instant for the {@link #ENTRY_TTL} cutoff
   * @return a mutable map from section key to that section's live local editors (never {@code
   *     null})
   */
  @NotNull
  private Map<String, List<Entry>> liveLocalEntries(@NotNull String topic, @NotNull Instant now) {
    Map<String, List<Entry>> result = new LinkedHashMap<>();
    Map<String, Map<String, Entry>> sections = byTopic.get(topic);
    if (sections == null) {
      return result;
    }
    Instant cutoff = now.minus(ENTRY_TTL);
    for (Map.Entry<String, Map<String, Entry>> sectionEntry : sections.entrySet()) {
      List<Entry> live = new ArrayList<>();
      for (Entry e : sectionEntry.getValue().values()) {
        if (!e.lastHeartbeat().isBefore(cutoff)) {
          live.add(e);
        }
      }
      if (!live.isEmpty()) {
        result.put(sectionEntry.getKey(), live);
      }
    }
    return result;
  }

  /**
   * Folds every non-expired peer partition for a topic into an in-progress snapshot, skipping any
   * user the local half (or an earlier partition) already contributed to that section.
   *
   * @param topic canonical topic
   * @param now reference instant for the {@link #REMOTE_PARTITION_TTL} cutoff
   * @param target the mutable snapshot being built, extended in place
   */
  private void mergeRemoteEntries(
      @NotNull String topic, @NotNull Instant now, @NotNull Map<String, List<Entry>> target) {
    Map<String, RemotePartition> origins = remoteByTopic.get(topic);
    if (origins == null || origins.isEmpty()) {
      return;
    }
    Instant cutoff = now.minus(REMOTE_PARTITION_TTL);
    for (RemotePartition partition : origins.values()) {
      if (partition.receivedAt().isBefore(cutoff)) {
        continue;
      }
      for (Map.Entry<String, List<PresenceEditor>> sectionEntry : partition.sections().entrySet()) {
        List<Entry> merged =
            target.computeIfAbsent(sectionEntry.getKey(), ignored -> new ArrayList<>());
        for (PresenceEditor editor : sectionEntry.getValue()) {
          boolean alreadyPresent =
              merged.stream().anyMatch(e -> e.userId().equals(editor.userId()));
          if (!alreadyPresent) {
            merged.add(new Entry(editor.userId(), editor.displayName(), partition.receivedAt()));
          }
        }
      }
    }
    target.entrySet().removeIf(e -> e.getValue().isEmpty());
  }

  /**
   * Caps an inbound partition to {@link #MAX_SECTIONS_PER_TOPIC} sections and {@link
   * #MAX_EDITORS_PER_REMOTE_SECTION} editors each, so a malformed or hostile publisher cannot grow
   * the mirror without bound. Mirrors the caps the local half already carries; the caller has
   * already validated the section-key shape.
   *
   * @param sections the raw partition from the wire
   * @return an immutable, bounded copy
   */
  @NotNull
  private static Map<String, List<PresenceEditor>> boundedPartition(
      @NotNull Map<String, List<PresenceEditor>> sections) {
    Map<String, List<PresenceEditor>> bounded = new LinkedHashMap<>();
    for (Map.Entry<String, List<PresenceEditor>> sectionEntry : sections.entrySet()) {
      if (bounded.size() >= MAX_SECTIONS_PER_TOPIC) {
        break;
      }
      List<PresenceEditor> editors = sectionEntry.getValue();
      if (editors.isEmpty()) {
        continue;
      }
      bounded.put(
          sectionEntry.getKey(),
          List.copyOf(
              editors.subList(0, Math.min(editors.size(), MAX_EDITORS_PER_REMOTE_SECTION))));
    }
    return Collections.unmodifiableMap(bounded);
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
   * One editor as it crosses the cross-replica presence channel (ADR-0126): identity and label
   * only. Deliberately timestamp-free — a mirrored partition's freshness is judged by its arrival
   * instant against {@link #REMOTE_PARTITION_TTL}, so no peer's clock is ever trusted, and the wire
   * frame stays byte-identical in shape to what the browser already receives.
   *
   * @param userId stable identifier of the editing user
   * @param displayName name to show in the UI (already redacted for guests by the publisher)
   */
  public record PresenceEditor(String userId, String displayName) {}

  /**
   * One peer replica's complete presence state for one topic, plus the local instant it arrived.
   * Replaced as a unit by that origin's next gossip and expired as a unit by {@link
   * #reapExpiredRemote(Instant)} — never mutated entry by entry, which is what keeps the mirror
   * convergent without delete frames or message ordering.
   *
   * @param sections the origin's editors per section key (immutable, already bounded)
   * @param receivedAt local instant at which this partition was applied
   */
  private record RemotePartition(Map<String, List<PresenceEditor>> sections, Instant receivedAt) {}

  /**
   * Lightweight key pair referencing a single section of a single topic. Used to communicate which
   * rooms changed after a reap.
   *
   * @param topic canonical topic
   * @param sectionKey section key
   */
  public record TopicSectionRef(String topic, String sectionKey) {}
}
