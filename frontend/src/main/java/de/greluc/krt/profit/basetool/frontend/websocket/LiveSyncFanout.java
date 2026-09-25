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
 * Cross-replica fan-out for the live-sync relay's {@code changed} signal (ADR-0094) and
 * editor-presence snapshot (ADR-0126).
 *
 * <p>Callers relay locally first and then publish, so a fan-out failure degrades to single-instance
 * behavior. {@link NoopLiveSyncFanout} is the default binding; the Redis binding uses separate
 * channels for changes and presence.
 */
public interface LiveSyncFanout {

  /**
   * Publishes an already locally relayed {@code changed} signal to peer replicas. Must never throw;
   * failures are recorded as a metric.
   *
   * @param canonicalTopic the canonical topic the signal belongs to
   * @param sections the sanitised section keys to re-render on peers
   */
  void publish(@NotNull String canonicalTopic, @NotNull List<String> sections);

  /**
   * Publishes this instance's complete editor-presence state for one topic to peer replicas
   * (ADR-0126), on every local change and every reaper tick. Must never throw. An empty map tells
   * peers this instance has no editors on the topic.
   *
   * @param canonicalTopic the canonical topic the snapshot belongs to
   * @param sections this instance's editors per section key (possibly empty)
   */
  void publishPresence(
      @NotNull String canonicalTopic,
      @NotNull Map<String, List<LiveSyncPresenceService.PresenceEditor>> sections);
}
