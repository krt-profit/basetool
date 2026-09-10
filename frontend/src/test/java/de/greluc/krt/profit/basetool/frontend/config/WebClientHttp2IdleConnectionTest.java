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

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * A pooled HTTP/2 connection must survive being idle for longer than the read timeout.
 *
 * <p><b>This is the regression the first HTTP/2 run actually shipped</b>, and it did not look like
 * a transport problem from the outside. The connector attached a channel-level {@code
 * ReadTimeoutHandler} through {@code doOnConnected}, which bounds silence on a <em>connection</em>.
 * That was harmless while a hundred connections each carried one request: the timeout closed an
 * idle spare. Under HTTP/2 with strict connection reuse one or two connections carry everything and
 * are idle between bursts by design, so the timeout closed the connection the whole application was
 * riding, and whatever was in flight died with {@code PrematureCloseException: Connection
 * prematurely closed BEFORE response}.
 *
 * <p>The symptom was five unrelated E2E write flows failing on {@code window.__krtNoReload},
 * because {@code krtFetch} falls back to a full page reload when a call fails. 169 log lines, none
 * of them carrying a correlation id — which is the tell that the timeout fired with no request in
 * flight.
 *
 * <p>So the assertion is not "a request succeeds" but "the <b>same socket</b> served both", because
 * a connection that was closed and silently replaced would still answer the second request and
 * would still leave the race that broke production behaviour.
 */
@SpringBootTest(properties = "app.http.read-timeout=300ms")
class WebClientHttp2IdleConnectionTest {

  /** Comfortably longer than the 300 ms read timeout this context pins, and short enough to run. */
  private static final Duration IDLE = Duration.ofMillis(1200);

  /** The request/response client — the one that negotiates HTTP/2. */
  @Autowired private WebClient liveSyncAuthWebClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /** Every distinct peer the server saw. One entry means one socket served both requests. */
  private final Set<SocketAddress> peers = ConcurrentHashMap.newKeySet();

  private DisposableServer server;

  @BeforeEach
  void startServer() {
    peers.clear();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
            .secure(
                spec ->
                    spec.sslContext(
                        (reactor.netty.tcp.SslProvider.GenericSslContextSpec<?>)
                            Http2SslContextSpec.forServer(TestTls.serverKeyManagerFactory())))
            .handle(
                (request, response) -> {
                  request.withConnection(
                      connection -> peers.add(connection.channel().remoteAddress()));
                  return response.header("Content-Type", "text/plain").sendString(Mono.just("ok"));
                })
            .bindNow();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.disposeNow(Duration.ofSeconds(10));
    }
  }

  @Test
  @DisplayName("an idle HTTP/2 connection is not closed by the read timeout")
  void idleConnectionsSurviveTheReadTimeout() throws Exception {
    assertThat(get()).isEqualTo("ok");
    assertThat(peers).as("the first call opened exactly one connection").hasSize(1);

    // The idle window the bug needed. Nothing is in flight; with a channel-level ReadTimeoutHandler
    // armed this is where the connection was closed underneath the pool.
    Thread.sleep(IDLE.toMillis());

    assertThat(get()).as("the second call must still be served").isEqualTo("ok");
    assertThat(peers)
        .as(
            "the same socket must have served both calls — a second peer address means the idle"
                + " connection was closed and replaced, which is the race that broke five E2E write"
                + " flows")
        .hasSize(1);
  }

  /**
   * Performs one GET against the local server.
   *
   * @return the response body
   */
  private String get() {
    return liveSyncAuthWebClient
        .get()
        .uri(java.net.URI.create("https://127.0.0.1:" + server.port() + "/probe"))
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(20));
  }
}
