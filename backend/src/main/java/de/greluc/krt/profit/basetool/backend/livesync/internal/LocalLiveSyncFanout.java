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

package de.greluc.krt.profit.basetool.backend.livesync.internal;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncFanout;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The {@link LiveSyncFanout} used when the Redis publisher is off; carries a frame nowhere
 * (ADR-0274).
 *
 * <p>The frontend's own live sync between browser tabs is unaffected; only the backend's own
 * announcements stop crossing.
 */
public class LocalLiveSyncFanout implements LiveSyncFanout {

  /**
   * Does nothing.
   *
   * @param topic ignored — there is no transport to carry it
   * @param sections ignored, for the same reason
   */
  @Override
  public void publish(@NotNull LiveSyncTopic topic, @NotNull List<String> sections) {}
}
