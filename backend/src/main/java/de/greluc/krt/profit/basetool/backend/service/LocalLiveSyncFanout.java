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
 * The {@link LiveSyncFanout} used when the Redis bridge is switched off — it carries a frame
 * nowhere (ADR-0143).
 *
 * <p>Not a degradation of the app's own live sync: the relay delivers to this instance's streams
 * <em>before</em> it reaches the fan-out, so with this bean in place two app clients on the same
 * backend still see each other's changes. What stops is the crossing — a browser will not learn of
 * an app write, and the app will not learn of a browser's, because nothing consumes or publishes on
 * the shared channel.
 *
 * <p>This is the default, and it is the right default: Redis is optional for this backend
 * (ADR-0084), so a test slice or a single-container run must start without it and behave sanely
 * rather than failing to wire.
 */
public class LocalLiveSyncFanout implements LiveSyncFanout {

  /**
   * Does nothing.
   *
   * @param topic ignored — local delivery already happened in the relay
   * @param sections ignored, for the same reason
   */
  @Override
  public void publish(@NotNull LiveSyncTopic topic, @NotNull List<String> sections) {
    // Intentionally empty: without the Redis bridge there is no peer to carry the frame to.
  }
}
