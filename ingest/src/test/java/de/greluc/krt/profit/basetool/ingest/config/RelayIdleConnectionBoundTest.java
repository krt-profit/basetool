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
 * The gateway's idle timeout for backend connections is shorter than the backend Tomcat's HTTP/1.1
 * keep-alive, read from the Tomcat protocol class on the classpath, with a margin (ADR-0204).
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
