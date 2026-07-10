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

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Cross-replica fan-out seam for the live-sync {@code changed} relay (ADR-0093).
 *
 * <p>The handler always relays a {@code changed} frame to its <em>own</em> local room first, then
 * calls {@link #publish(String, List)} so other frontend replicas can relay it to <em>their</em>
 * local rooms. The default binding is {@link NoopLiveSyncFanout} (single-instance: publish is a
 * no-op, local relay is the whole story); the Redis binding ({@code RedisLiveSyncFanout}) publishes
 * to a shared channel and each instance skips its own message on consume. Because local relay
 * happens first, a Redis outage degrades to exactly the single-instance behaviour — never worse.
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
}
