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
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

/**
 * What ADR-0161 §8.1 actually bought, asserted against a real TLS handshake.
 *
 * <p>The finding it fixes was invisible from configuration alone: both applications had set {@code
 * server.http2.enabled: true} since they were written, and the frontend's outbound client still
 * spoke HTTP/1.1 — because an {@code SslContext} built with no {@code applicationProtocolConfig}
 * advertises no ALPN protocol, so there was nothing for the server to select. A test that read
 * properties would have reported the system as already on HTTP/2. This one reads the protocol the
 * two ends agreed on, from the server's {@code SslHandler}, after the handshake.
 *
 * <p>The server is a real Reactor Netty HTTP/2 endpoint serving the committed test TLS material
 * (`docker/test-tls`, ADR-0139) — never a production artefact, and the `test` profile's client
 * trusts anything, so the handshake succeeds without installing a thing.
 */
@SpringBootTest
class WebClientHttp2NegotiationTest {

  /** The request/response client — the hop §8.1 is about. */
  @Autowired private WebClient liveSyncAuthWebClient;

  /** The SSE relay's client, which must stay on HTTP/1.1. */
  @Autowired private WebClient sseWebClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /** The ALPN protocol the server saw on the connection that carried the last request. */
  private final AtomicReference<String> negotiated = new AtomicReference<>("none");

  /**
   * The distinct peer addresses the server saw, which is how many TCP connections were opened.
   *
   * <p>Counting {@code doOnConnection} callbacks does <b>not</b> work here and getting that wrong
   * is how this test first "disproved" multiplexing that was in fact happening: under HTTP/2
   * Reactor Netty raises a connection observation per <em>stream</em> channel, so forty calls on
   * two sockets reported forty. A stream channel reports its parent's {@code remoteAddress()}, so
   * distinct peers is the count that means what it says on both protocols.
   */
  private final Set<SocketAddress> peers = ConcurrentHashMap.newKeySet();

  private DisposableServer server;

  @BeforeEach
  void startServer() {
    negotiated.set("none");
    peers.clear();
    server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
            .secure(
                spec ->
                    // Cast to the generic spec on purpose: Http2SslContextSpec implements both
                    // interfaces and Java picks the more specific -- and deprecated -- overload
                    // without it, failing the -Werror build on a call that is otherwise correct.
                    spec.sslContext(
                        (reactor.netty.tcp.SslProvider.GenericSslContextSpec<?>)
                            Http2SslContextSpec.forServer(TestTls.serverKeyManagerFactory())))
            .handle(
                (request, response) -> {
                  request.withConnection(
                      connection -> {
                        negotiated.set(applicationProtocol(connection.channel()));
                        peers.add(connection.channel().remoteAddress());
                      });
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
  @DisplayName("the request/response client negotiates HTTP/2")
  void requestClientNegotiatesHttp2() {
    assertThat(get(liveSyncAuthWebClient)).isEqualTo("ok");

    // "h2" is the ALPN identifier, and the server is the honest place to read it: it reports what
    // was agreed, not what the client hoped to offer.
    assertThat(negotiated.get()).isEqualTo("h2");
  }

  @Test
  @DisplayName("the SSE relay stays on HTTP/1.1, and that is a decision rather than an oversight")
  void streamingClientStaysOnHttp11() {
    assertThat(get(sseWebClient)).isEqualTo("ok");

    // Asserted as "not h2" rather than as an exact string: a client that offers no ALPN extension
    // at all leaves the JDK engine reporting an empty protocol on some providers and null on
    // others, and pinning which one would make this a test of the TLS stack instead of the
    // decision. What matters is that a thousand long-lived viewer streams are not multiplexed onto
    // a handful of connections behind one flow-control window.
    assertThat(negotiated.get()).isNotEqualTo("h2");
  }

  @Test
  @DisplayName("forty concurrent calls ride a handful of connections, not forty")
  void concurrentCallsAreMultiplexed() {
    // The saving itself. Under HTTP/1.1 each in-flight call holds one pooled connection, which is
    // why `frontend-pool` was raised to 100 and aligned with the bulkhead. Under HTTP/2 with
    // maxConcurrentStreams pinned to 20 (Tomcat 11.0.25 executes no more than that per connection)
    // forty concurrent calls need two or three -- and crucially NOT one, which is what an
    // unconfigured H2 pool would have used while eighty per cent of the work queued server-side.
    Flux.range(0, 40)
        .flatMap(
            i ->
                liveSyncAuthWebClient
                    .get()
                    .uri(uri())
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
            40)
        .blockLast(Duration.ofSeconds(30));

    assertThat(negotiated.get()).isEqualTo("h2");
    assertThat(peers)
        .as("40 concurrent calls at 20 streams per connection, one socket per peer address")
        .hasSizeLessThanOrEqualTo(8);
  }

  @Test
  @DisplayName("the same load on HTTP/1.1 needs a socket per call, which is the cost being removed")
  void theHttp11PathStillNeedsAConnectionPerCall() {
    // The control. Without it "eight or fewer sockets" is a number with nothing to compare it to,
    // and a regression that quietly dropped back to HTTP/1.1 would still satisfy the case above on
    // a fast enough machine, because sequential reuse also keeps the socket count low. The SSE
    // client is the same connector code with http2 = false, so this measures the protocol and not
    // a second configuration.
    Flux.range(0, 40)
        .flatMap(
            i ->
                sseWebClient
                    .get()
                    .uri(uri())
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
            40)
        .blockLast(Duration.ofSeconds(30));

    assertThat(negotiated.get()).isNotEqualTo("h2");
    assertThat(peers)
        .as("HTTP/1.1 carries one call per socket while it is in flight")
        .hasSizeGreaterThan(8);
  }

  /**
   * Performs one GET against the local server and returns the body.
   *
   * @param client the client under test
   * @return the response body
   */
  private String get(WebClient client) {
    return client
        .get()
        .uri(uri())
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(20));
  }

  /**
   * The absolute URI of the local probe endpoint.
   *
   * <p>Absolute on purpose: both clients carry a {@code baseUrl} pointing at the real backend, and
   * an absolute URI overrides it — which is what lets this test exercise the production connector
   * wiring rather than a connector it built itself.
   *
   * @return the probe URI on the ephemeral port the server bound
   */
  private java.net.URI uri() {
    return java.net.URI.create("https://127.0.0.1:" + server.port() + "/probe");
  }

  /**
   * Reads the negotiated ALPN protocol off whichever channel in the chain carries the TLS handler.
   *
   * <p>Under HTTP/2 the handler is invoked on a stream channel and the {@code SslHandler} lives on
   * its parent; under HTTP/1.1 both are the same channel. Walking up rather than branching on the
   * protocol keeps the reader out of the answer.
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
