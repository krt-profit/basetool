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
 * Server-side seam for publishing a live-sync {@code changed} signal from a controller rather than
 * from a client socket (REQ-FE-015, ADR-0093).
 *
 * <p>Most {@code changed} signals originate from the acting user's own {@code /ws/sync} socket. But
 * some mutations have no socket to publish from — chiefly an <b>anonymous guest order create</b>,
 * where the guest is not authenticated and therefore never opened a socket, yet the staff {@code
 * orders} queue every logged-in viewer is subscribed to must still update in place. Such
 * controllers inject this seam and call {@link #publish(String, List)} after a successful mutation;
 * the signal is relayed to this instance's local room and fanned out to peer replicas exactly like
 * a client publish (the sections are re-validated against the topic class's whitelist). Depending
 * on the seam rather than the WebSocket handler keeps controllers decoupled from the relay
 * internals and lets a controller test assert the publish with a simple mock.
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
