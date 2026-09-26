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
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Publishes a live-sync {@code changed} signal from a controller rather than a client socket
 * (REQ-FE-015, ADR-0094), for mutations whose actor is not subscribed to the affected room. The
 * signal is relayed locally and fanned out like a client publish, with sections re-validated.
 */
@Component
@RequiredArgsConstructor
public class LiveSyncLocalBus {

  private final LiveSyncWebSocketHandler handler;

  /**
   * Publishes a server-originated {@code changed} signal for a topic.
   *
   * @param topic the canonical topic string (e.g. {@code orders}); an unknown topic is ignored
   * @param sections the changed section keys (filtered to the topic class's whitelist)
   */
  public void publish(@NotNull String topic, @NotNull List<String> sections) {
    handler.publishFromServer(topic, sections);
  }
}
