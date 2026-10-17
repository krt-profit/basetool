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

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The live-sync rooms of one replica (REQ-FE-015, ADR-0094): which socket decorators sit in which
 * {@link LiveSyncTopic} room, and the {@code changed} and {@code presence} frames written to them.
 *
 * <p>Binds the room gauges and counts every relayed and dropped frame (REQ-OBS-006).
 */
@Slf4j(topic = "de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncWebSocketHandler")
final class LiveSyncRooms {

  private final LiveSyncPresenceService presenceService;
  private final LiveSyncFanout fanout;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  private final Map<String, Set<WebSocketSession>> sessionsByTopic = new ConcurrentHashMap<>();

  /**
   * Creates the room registry and binds the session, subscription and peer-room gauges.
   *
   * @param presenceService in-memory editor-presence store
   * @param fanout cross-replica fan-out seam (no-op when single-instance)
   * @param objectMapper Jackson mapper for the wire frames
   * @param meterRegistry the registry the gauges and relay counters bind to
   */
  LiveSyncRooms(
      @NotNull LiveSyncPresenceService presenceService,
      @NotNull LiveSyncFanout fanout,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry) {
    this.presenceService = presenceService;
    this.fanout = fanout;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    Gauge.builder(
            MetricNames.PRESENCE_WS_SESSIONS,
            sessionsByTopic,
            map -> map.values().stream().mapToInt(Set::size).sum())
        .description("Live live-sync WebSocket sessions summed across all topic rooms.")
        .register(meterRegistry);
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      Gauge.builder(MetricNames.LIVESYNC_SUBSCRIPTIONS, this, r -> r.subscriptionCount(topicClass))
          .tag(MetricNames.TAG_TOPIC_CLASS, topicClass.metricLabel())
          .description("Live live-sync subscriptions for this topic class.")
          .register(meterRegistry);
      Gauge.builder(MetricNames.LIVESYNC_PEER_ROOMS, this, r -> r.peerRoomCount(topicClass))
          .tag(MetricNames.TAG_TOPIC_CLASS, topicClass.metricLabel())
          .description("Live rooms of this topic class holding two or more subscribers.")
          .register(meterRegistry);
    }
  }

  /**
   * Counts live sessions in rooms of a given topic class (backs the per-class subscriptions gauge).
   *
   * @param topicClass the class to count
   * @return the number of live sockets across that class's rooms
   */
  private int subscriptionCount(@NotNull LiveSyncTopicClass topicClass) {
    int total = 0;
    for (Map.Entry<String, Set<WebSocketSession>> room : sessionsByTopic.entrySet()) {
      LiveSyncTopic topic = LiveSyncTopic.parse(room.getKey());
      if (topic != null && topic.topicClass() == topicClass) {
        total += room.getValue().size();
      }
    }
    return total;
  }

  /**
   * Counts the rooms of a topic class holding two or more sockets, for the {@code
   * basetool_livesync_peer_rooms} gauge.
   *
   * @param topicClass the class to count
   * @return the number of rooms of that class with at least two live sockets
   */
  private int peerRoomCount(@NotNull LiveSyncTopicClass topicClass) {
    int rooms = 0;
    for (Map.Entry<String, Set<WebSocketSession>> room : sessionsByTopic.entrySet()) {
      LiveSyncTopic topic = LiveSyncTopic.parse(room.getKey());
      if (topic != null && topic.topicClass() == topicClass && room.getValue().size() >= 2) {
        rooms++;
      }
    }
    return rooms;
  }

  /**
   * Adds a socket's decorator to a topic's room atomically, so a concurrent close cannot orphan it.
   *
   * @param decorated the decorator to register (never the raw session)
   * @param topic the room to join
   */
  void join(@NotNull WebSocketSession decorated, @NotNull LiveSyncTopic topic) {
    sessionsByTopic.compute(
        topic.canonical(),
        (_, set) -> {
          Set<WebSocketSession> mates = (set != null) ? set : ConcurrentHashMap.newKeySet();
          mates.add(decorated);
          return mates;
        });
  }

  /**
   * Removes a socket's decorator from a room under the entry's bin lock, unmapping the entry when
   * it becomes empty. The inverse of {@link #join}.
   *
   * @param decorated the decorator to deregister
   * @param canonicalTopic the canonical topic of the room to leave
   * @return the sockets still in the room, or {@code null} when the room is gone
   */
  @Nullable
  Set<WebSocketSession> leave(@NotNull WebSocketSession decorated, @NotNull String canonicalTopic) {
    return sessionsByTopic.computeIfPresent(
        canonicalTopic,
        (_, set) -> {
          set.remove(decorated);
          return set.isEmpty() ? null : set;
        });
  }

  /**
   * Sends a sanitised {@code changed} frame to every socket in the topic's room except {@code
   * origin}.
   *
   * @param topic the topic whose room receives the frame
   * @param sections the sanitised section keys
   * @param origin the local originating session to exclude, or {@code null}
   */
  void relayChanged(
      @NotNull LiveSyncTopic topic, @NotNull List<String> sections, WebSocketSession origin) {
    Set<WebSocketSession> mates = sessionsByTopic.get(topic.canonical());
    if (mates == null || mates.isEmpty()) {
      return;
    }
    String payload;
    try {
      ObjectNode root = objectMapper.createObjectNode();
      root.put("type", "changed");
      root.put("topic", topic.canonical());
      ArrayNode sectionsArray = root.putArray("sections");
      for (String key : sections) {
        sectionsArray.add(key);
      }
      payload = objectMapper.writeValueAsString(root);
    } catch (JacksonException e) {
      log.warn("Failed to serialise change relay for topic {}", topic.canonical(), e);
      return;
    }
    TextMessage message = new TextMessage(payload);
    Counter frames = frameCounter(topic, MetricNames.FRAME_CHANGED);
    for (WebSocketSession session : List.copyOf(mates)) {
      if (session == origin) {
        continue;
      }
      if (sendSafe(session, message, topic)) {
        frames.increment();
      }
    }
  }

  /**
   * Broadcasts a topic's merged presence snapshot locally and gossips this instance's part to peer
   * replicas (ADR-0126); called on every local presence change.
   *
   * @param topic the topic whose presence changed locally
   */
  void broadcastLocalPresenceChange(@NotNull LiveSyncTopic topic) {
    fanout.publishPresence(
        topic.canonical(), presenceService.localSnapshot(topic.canonical(), Instant.now()));
    broadcastSnapshot(topic);
  }

  /**
   * Writes a topic's merged presence snapshot to every socket of its room, without gossiping it.
   *
   * @param topic the topic whose room receives the snapshot
   */
  void broadcastSnapshot(@NotNull LiveSyncTopic topic) {
    Set<WebSocketSession> mates = sessionsByTopic.get(topic.canonical());
    if (mates == null || mates.isEmpty()) {
      return;
    }
    String payload;
    try {
      payload = objectMapper.writeValueAsString(buildSnapshot(topic));
    } catch (JacksonException e) {
      log.warn("Failed to serialise presence snapshot for topic {}", topic.canonical(), e);
      return;
    }
    TextMessage message = new TextMessage(payload);
    Counter frames = frameCounter(topic, MetricNames.FRAME_SNAPSHOT);
    for (WebSocketSession session : List.copyOf(mates)) {
      if (sendSafe(session, message, topic)) {
        frames.increment();
      }
    }
  }

  /**
   * Writes a topic's presence snapshot to one freshly subscribed socket.
   *
   * @param session the subscribed decorator
   * @param topic the presence-enabled topic
   */
  void sendSnapshot(@NotNull WebSocketSession session, @NotNull LiveSyncTopic topic) {
    try {
      String payload = objectMapper.writeValueAsString(buildSnapshot(topic));
      if (sendSafe(session, new TextMessage(payload), topic)) {
        frameCounter(topic, MetricNames.FRAME_SNAPSHOT).increment();
      }
    } catch (JacksonException e) {
      log.warn("Failed to serialise initial presence snapshot for topic {}", topic.canonical(), e);
    }
  }

  private ObjectNode buildSnapshot(@NotNull LiveSyncTopic topic) {
    Map<String, List<LiveSyncPresenceService.Entry>> snapshot =
        presenceService.snapshot(topic.canonical(), Instant.now());
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "presence");
    root.put("topic", topic.canonical());
    ObjectNode sections = root.putObject("sections");
    for (Map.Entry<String, List<LiveSyncPresenceService.Entry>> e : snapshot.entrySet()) {
      ArrayNode editors = sections.putArray(e.getKey());
      for (LiveSyncPresenceService.Entry editor : e.getValue()) {
        ObjectNode editorNode = editors.addObject();
        editorNode.put("userId", editor.userId());
        editorNode.put("displayName", editor.displayName());
      }
    }
    return root;
  }

  /**
   * Writes one frame to a session; a failed send is counted as a {@code send_failed} relay drop.
   *
   * @param session the target session
   * @param message the frame to write
   * @param topic the topic whose class tags the drop metric
   * @return {@code true} if the frame was written, {@code false} if the session was closed or the
   *     write failed
   */
  private boolean sendSafe(
      @NotNull WebSocketSession session,
      @NotNull TextMessage message,
      @NotNull LiveSyncTopic topic) {
    if (!session.isOpen()) {
      return false;
    }
    try {
      session.sendMessage(message);
      return true;
    } catch (IOException | IllegalStateException | SessionLimitExceededException e) {
      log.debug("Drop live-sync frame to closed/broken/overflowed session {}", session.getId(), e);
      droppedCounter(topic, MetricNames.DROPPED_SEND_FAILED).increment();
      return false;
    }
  }

  private Counter frameCounter(@NotNull LiveSyncTopic topic, @NotNull String type) {
    return meterRegistry.counter(
        MetricNames.PRESENCE_RELAY_FRAMES,
        MetricNames.TAG_TYPE,
        type,
        MetricNames.TAG_TOPIC_CLASS,
        topic.topicClass().metricLabel());
  }

  /**
   * Returns the {@link MetricNames#PRESENCE_RELAY_DROPPED} counter for one reason and topic class.
   *
   * @param topic the topic whose class tags the metric
   * @param reason the bounded drop reason
   * @return the counter to increment
   */
  Counter droppedCounter(@NotNull LiveSyncTopic topic, @NotNull String reason) {
    return meterRegistry.counter(
        MetricNames.PRESENCE_RELAY_DROPPED,
        MetricNames.TAG_REASON,
        reason,
        MetricNames.TAG_TOPIC_CLASS,
        topic.topicClass().metricLabel());
  }
}
