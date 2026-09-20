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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.apache.coyote.http2.Http2Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frontend must drop an idle backend connection before the backend does.
 *
 * <p>Both ends bound the same idle connection and only the shorter bound is safe. The backend's
 * embedded Tomcat closes an idle HTTP/2 connection after {@code Http2Protocol.keepAliveTimeout};
 * the frontend's pool keeps offering one for {@code BACKEND_POOL_MAX_IDLE_TIME}. Whichever side
 * waits longer is the side that hands out a socket the peer has already torn down — and under
 * HTTP/2 with strict connection reuse that socket is carrying the whole burst, so a single mistimed
 * acquisition loses every stream on it at once rather than one request.
 *
 * <p>Both were 20&nbsp;s until 2026-09-20, which is not an alignment but a collision: the client's
 * window necessarily opens later than the server's (it starts when the last response finished
 * arriving, the server's when it finished being written), so equal lengths guarantee a slice of
 * time in which only one side still believes in the connection. Production lost fourteen streams on
 * one connection in the same millisecond against a backend that neither restarted nor logged
 * anything but 200s.
 *
 * <p>The server's side of the comparison is read off the {@link Http2Protocol} <b>on the
 * classpath</b> rather than typed in, so a Tomcat upgrade that lowers the default fails here
 * instead of in production. Nothing in this repository calls {@code setKeepAliveTimeout}, so the
 * default is what the backend runs; both modules resolve Tomcat through the same Spring Boot BOM,
 * so the version read here is the version the backend serves with.
 */
class WebClientBackendPoolIdleBoundTest {

  /**
   * How much shorter than the peer's keep-alive the pool's bound has to be. A margin rather than a
   * mere inequality: the two clocks start at different moments and neither side's timer is exact,
   * so "shorter by a millisecond" would still race. Half the peer's window is the smallest ratio
   * that is obviously not a coincidence.
   */
  private static final double REQUIRED_MARGIN_FACTOR = 2.0d;

  @Test
  @DisplayName("the pool evicts an idle backend connection well before Tomcat closes it")
  void poolIdleBoundStaysWellUnderTheBackendKeepAlive() {
    Duration tomcatKeepAlive = Duration.ofMillis(new Http2Protocol().getKeepAliveTimeout());
    Duration poolIdleBound = WebClientConfig.BACKEND_POOL_MAX_IDLE_TIME;

    assertThat(poolIdleBound)
        .as(
            "the backend-facing pool must evict an idle connection at most 1/%s of Tomcat's"
                + " %s keep-alive (currently %s) — equal bounds are a collision, not an alignment",
            REQUIRED_MARGIN_FACTOR, tomcatKeepAlive, poolIdleBound)
        .isLessThanOrEqualTo(
            Duration.ofMillis((long) (tomcatKeepAlive.toMillis() / REQUIRED_MARGIN_FACTOR)));
  }
}
