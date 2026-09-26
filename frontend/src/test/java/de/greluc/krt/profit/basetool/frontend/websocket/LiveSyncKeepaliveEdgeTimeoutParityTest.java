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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link LiveSyncWebSocketHandler#KEEPALIVE_INTERVAL} is at most half the edge
 * proxy's {@code proxy_read_timeout}, so idle sockets are never closed by the proxy.
 */
class LiveSyncKeepaliveEdgeTimeoutParityTest {

  /** The edge's main nginx configuration, relative to the frontend module directory. */
  private static final Path EDGE_NGINX_CONF = Path.of("..", "docker", "edge", "nginx.conf");

  /**
   * Matches the http-level {@code proxy_read_timeout <n>s;} directive and captures up to nine
   * digits of seconds.
   */
  private static final Pattern PROXY_READ_TIMEOUT =
      Pattern.compile("^\\s*proxy_read_timeout\\s+(\\d{1,9})s\\s*;", Pattern.MULTILINE);

  /**
   * The keepalive sweep must fire at least twice within the edge's idle-read window, so a socket
   * survives one missed tick.
   *
   * @throws IOException if the edge configuration cannot be read
   */
  @Test
  void keepaliveInterval_leavesAtLeastDoubleMarginUnderTheEdgeReadTimeout() throws IOException {
    String conf = Files.readString(EDGE_NGINX_CONF, StandardCharsets.UTF_8);
    Matcher matcher = PROXY_READ_TIMEOUT.matcher(conf);
    assertThat(matcher.find())
        .as("docker/edge/nginx.conf declares a proxy_read_timeout in whole seconds")
        .isTrue();
    Duration edgeReadTimeout = Duration.ofSeconds(Long.parseLong(matcher.group(1)));

    assertThat(LiveSyncWebSocketHandler.KEEPALIVE_INTERVAL.multipliedBy(2))
        .as(
            "the /ws/sync keepalive must ping at least twice inside the edge's %s idle-read window;"
                + " lowering the proxy timeout or raising the keepalive brings back the 90-second"
                + " reconnect-and-resync loop",
            edgeReadTimeout)
        .isLessThanOrEqualTo(edgeReadTimeout);
  }
}
