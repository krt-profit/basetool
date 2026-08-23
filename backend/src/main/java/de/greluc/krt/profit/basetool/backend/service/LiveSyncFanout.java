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

import de.greluc.krt.profit.basetool.backend.support.LiveSyncTopic;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The seam that carries an app-originated {@code changed} frame beyond this JVM (ADR-0143).
 *
 * <p>Two implementations: {@link LocalLiveSyncFanout} does nothing, which is correct because the
 * relay has already delivered to this instance's own streams before calling here; {@link
 * RedisLiveSyncFanout} additionally publishes onto the channel the web frontend both publishes to
 * and consumes, which is what makes an app write refresh open browsers.
 *
 * <p>Implementations must not throw. A fan-out failure is a degradation — peers keep their old view
 * until they refresh on their own cadence — and must never turn a successful mutation's follow-up
 * signal into a failed request.
 */
public interface LiveSyncFanout {

  /**
   * Carries one frame to peers.
   *
   * @param topic the room, already parsed and canonical
   * @param sections the section keys, already clipped to the topic class's whitelist and non-empty
   */
  void publish(@NotNull LiveSyncTopic topic, @NotNull List<String> sections);
}
