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
 * Single-instance {@link LiveSyncFanout}: publishing is a no-op because the handler's local relay
 * is the whole story when the frontend runs as one replica (ADR-0094).
 *
 * <p>Instantiated as the fallback in {@code LiveSyncWebSocketConfig} via {@code
 * fanoutProvider.getIfAvailable(NoopLiveSyncFanout::new)}, so a registered {@link LiveSyncFanout}
 * bean (the Redis binding, when enabled) is used and this no-op is created only when none is — the
 * {@code test} profile, which loads no Redis, keeps a working, dependency-free fan-out. Keeping the
 * seam uniform means the handler's call path is identical whether or not Redis is wired.
 */
public class NoopLiveSyncFanout implements LiveSyncFanout {

  /**
   * No-op: the local relay already delivered the signal to this instance's rooms, and there are no
   * peer replicas to notify.
   *
   * @param canonicalTopic the canonical topic (unused)
   * @param sections the section keys (unused)
   */
  @Override
  public void publish(@NotNull String canonicalTopic, @NotNull List<String> sections) {
    // Single-instance: nothing to fan out.
  }

  /**
   * No-op: with one replica the local presence store is already the complete picture, so there is
   * no peer to gossip the snapshot to and nothing to merge back (ADR-0126).
   *
   * @param canonicalTopic the canonical topic (unused)
   * @param sections this instance's editors per section (unused)
   */
  @Override
  public void publishPresence(
      @NotNull String canonicalTopic,
      @NotNull Map<String, List<LiveSyncPresenceService.PresenceEditor>> sections) {
    // Single-instance: the local presence store is the whole truth.
  }
}
