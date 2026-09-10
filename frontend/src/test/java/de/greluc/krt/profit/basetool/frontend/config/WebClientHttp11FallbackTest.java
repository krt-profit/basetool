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

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
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
 * The way back from HTTP/2, without a redeploy.
 *
 * <p>ADR-0161 §8.1 asks for a load test before the switch is trusted, and a load test that cannot
 * be undone from configuration is a load test nobody runs on production. {@code
 * app.http.backend-protocol=HTTP11} is that undo, and this pins it: with the property set, the same
 * client that negotiates {@code h2} in {@link WebClientHttp2NegotiationTest} negotiates nothing
 * against a server that is still offering it.
 *
 * <p>A separate class rather than a nested one because the property has to be set before the
 * context is built, and that means a second context.
 */
@SpringBootTest(properties = "app.http.backend-protocol=HTTP11")
class WebClientHttp11FallbackTest {

  /** The request/response client, which the property is expected to hold on HTTP/1.1. */
  @Autowired private WebClient liveSyncAuthWebClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

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
                      connection -> negotiated.set(applicationProtocol(connection.channel())));
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
    String body =
        liveSyncAuthWebClient
            .get()
            .uri(java.net.URI.create("https://127.0.0.1:" + server.port() + "/probe"))
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(20));

    assertThat(body).isEqualTo("ok");

    // The server here is the same HTTP/2-capable one the other class uses, so a failure means the
    // property was ignored rather than that the peer could not speak it.
    assertThat(negotiated.get()).isNotEqualTo("h2");
  }

  /**
   * Reads the negotiated ALPN protocol off whichever channel in the chain carries the TLS handler.
   *
   * @param channel the channel the request arrived on
   * @return the ALPN protocol, or {@code "none"} when the engine reports none
   */
  private static String applicationProtocol(Channel channel) {
    for (Channel current = channel; current != null; current = current.parent()) {
      SslHandler handler = current.pipeline().get(SslHandler.class);
      if (handler != null) {
        String protocol = handler.applicationProtocol();
        return protocol == null || protocol.isEmpty() ? "none" : protocol;
      }
    }
    return "none";
  }
}
