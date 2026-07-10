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
 * Single-instance {@link LiveSyncFanout}: publishing is a no-op because the handler's local relay
 * is the whole story when the frontend runs as one replica (ADR-0093).
 *
 * <p>Contributed as the fallback {@code @Bean} in {@code LiveSyncWebSocketConfig} via
 * {@code @ConditionalOnMissingBean}, so the Redis binding (when enabled) replaces it and the {@code
 * test} profile — which loads no Redis — keeps a working, dependency-free fan-out. Keeping the seam
 * uniform means the handler's call path is identical whether or not Redis is wired.
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
}
