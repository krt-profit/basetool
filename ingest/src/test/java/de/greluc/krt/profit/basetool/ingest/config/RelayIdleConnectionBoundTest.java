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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;

/**
 * The gateway must drop an idle backend connection before the backend does (ING-PERF-01, ADR-0204).
 *
 * <p>The reactive client this replaced pooled connections with no idle eviction at all, so a
 * connection the backend had already closed could be handed to the next relay — a {@code POST},
 * which nothing retries. The JDK client closes an idle HTTP/1.1 connection after {@code
 * jdk.httpclient.keepalive.timeout} (30&nbsp;s unless the property is set); the backend's embedded
 * Tomcat closes it after its HTTP/1.1 keep-alive, which the backend never configures and which
 * therefore is Tomcat's default. The relay is pinned to HTTP/1.1, so that is the pair that matters.
 *
 * <p>Tomcat's side is read off the protocol class <b>on the classpath</b> rather than typed in, so
 * a Tomcat upgrade that lowers the default fails here instead of in production; the ingest and the
 * backend resolve Tomcat through the same Spring Boot BOM. The margin mirrors the frontend's {@code
 * WebClientBackendPoolIdleBoundTest}: equal bounds are a collision, not an alignment, because the
 * two clocks start at different moments.
 */
class RelayIdleConnectionBoundTest {

  /** The JDK's documented default for {@code jdk.httpclient.keepalive.timeout}, in seconds. */
  private static final int JDK_DEFAULT_KEEPALIVE_SECONDS = 30;

  /** How much shorter than the server's keep-alive the client's idle bound has to be. */
  private static final double REQUIRED_MARGIN_FACTOR = 2.0d;

  @Test
  void theRelayClientDropsAnIdleConnectionWellBeforeTomcatDoes() {
    Duration clientIdleBound =
        Duration.ofSeconds(
            Integer.getInteger("jdk.httpclient.keepalive.timeout", JDK_DEFAULT_KEEPALIVE_SECONDS));
    Duration tomcatKeepAlive = Duration.ofMillis(new Http11NioProtocol().getKeepAliveTimeout());

    assertThat(clientIdleBound)
        .as(
            "the JDK client's idle bound (%s) must be at most 1/%s of Tomcat's HTTP/1.1 keep-alive"
                + " (%s)",
            clientIdleBound, REQUIRED_MARGIN_FACTOR, tomcatKeepAlive)
        .isLessThanOrEqualTo(
            Duration.ofMillis((long) (tomcatKeepAlive.toMillis() / REQUIRED_MARGIN_FACTOR)));
  }
}
