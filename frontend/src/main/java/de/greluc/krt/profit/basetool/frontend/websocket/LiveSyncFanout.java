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

package de.greluc.krt.profit.basetool.frontend.websocket;

import de.greluc.krt.profit.basetool.frontend.service.LiveSyncPresenceService;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-replica fan-out seam for the live-sync relay — the {@code changed} signal (ADR-0094) and
 * the editor-presence snapshot (ADR-0126).
 *
 * <p>The handler always relays a {@code changed} frame to its <em>own</em> local room first, then
 * calls {@link #publish(String, List)} so other frontend replicas can relay it to <em>their</em>
 * local rooms. The default binding is {@link NoopLiveSyncFanout} (single-instance: publish is a
 * no-op, local relay is the whole story); the Redis binding ({@code RedisLiveSyncFanout}) publishes
 * to a shared channel and each instance skips its own message on consume. Because local relay
 * happens first, a Redis outage degrades to exactly the single-instance behaviour — never worse.
 *
 * <p>{@link #publishPresence(String, Map)} follows the same local-first shape on a
 * <em>separate</em> channel. The two streams stay apart deliberately: the changed relay carries
 * correctness (a missed frame leaves a peer's data stale) and is event-driven, while presence
 * carries a cosmetic dot and is gossiped periodically, so a presence burst must never queue behind
 * — or be mistaken on a dashboard for — the changed relay.
 */
public interface LiveSyncFanout {

  /**
   * Publishes an already-locally-relayed {@code changed} signal to peer frontend replicas.
   * Fire-and-forget: an implementation must never throw to the caller (the local relay has already
   * happened) — it records a failure metric and returns instead.
   *
   * @param canonicalTopic the canonical topic string the signal belongs to
   * @param sections the sanitised section keys to re-render on peers
   */
  void publish(@NotNull String canonicalTopic, @NotNull List<String> sections);

  /**
   * Gossips this instance's complete editor-presence state for one topic to peer frontend replicas
   * (ADR-0126). Sent on every local presence change and re-sent on each reaper tick, so a peer that
   * missed a message — or started up after the fact — converges within one tick. Fire-and-forget
   * under the same contract as {@link #publish(String, List)}: never throws to the caller, because
   * the local dots have already been broadcast.
   *
   * <p>An <b>empty</b> {@code sections} map is a meaningful message, not a skippable one: it tells
   * peers this instance no longer has any editor on the topic, so they drop its partition
   * immediately instead of holding stale dots until it expires.
   *
   * @param canonicalTopic the canonical topic string the snapshot belongs to
   * @param sections this instance's editors per section key (possibly empty)
   */
  void publishPresence(
      @NotNull String canonicalTopic,
      @NotNull Map<String, List<LiveSyncPresenceService.PresenceEditor>> sections);
}
