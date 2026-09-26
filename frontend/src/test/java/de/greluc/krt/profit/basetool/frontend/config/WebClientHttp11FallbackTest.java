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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * Verifies that {@code app.http.backend-protocol=HTTP11} makes the backend client negotiate no
 * HTTP/2 against a server offering it (ADR-0161).
 */
class WebClientHttp11FallbackTest {

  /** The ALPN protocol the server saw. */
  private final AtomicReference<String> negotiated = new AtomicReference<>("none");

  private DisposableServer server;

  @BeforeEach
  void startServer() {
    negotiated.set("none");
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
                      connection ->
                          negotiated.set(
                              WebClientTestSupport.applicationProtocol(connection.channel())));
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
  @DisplayName("the property holds the request client on HTTP/1.1 against a server offering h2")
  void theFlagTurnsHttp2Off() {
    WebClient client =
        WebClientTestSupport.config(
                AppHttpProperties.BackendProtocol.HTTP11, AppHttpProperties.BackendCodec.CBOR)
            .liveSyncAuthWebClient();

    String body =
        client
            .get()
            .uri(java.net.URI.create("https://127.0.0.1:" + server.port() + "/probe"))
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(20));

    assertThat(body).isEqualTo("ok");

    assertThat(negotiated.get()).isNotEqualTo("h2");
  }
}
