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
 * Carries an app-originated {@code changed} frame beyond this JVM (ADR-0143).
 *
 * <p>Implementations must not throw: a fan-out failure only leaves peers with a stale view and must
 * never fail the originating request.
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
