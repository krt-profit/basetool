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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.UserLocaleRelayFilter;
import de.greluc.krt.profit.basetool.frontend.logging.WebClientLoggingFilter;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import java.time.Duration;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * The pieces every {@code WebClientConfig} test needs, in one place.
 *
 * <p>Three of them had grown their own copy of the same Reactor Netty HTTP/2 probe server and the
 * same {@code applicationProtocol(Channel)} walk, and two had grown their own copy of the
 * collaborator doubles {@code WebClientConfig}'s nine-argument constructor needs. That last one is
 * the expensive duplication: adding a collaborator meant hand-editing every copy, and a test that
 * fails to compile is a test nobody runs.
 */
final class WebClientTestSupport {

  /** Not instantiable. */
  private WebClientTestSupport() {}

  /**
   * Builds the real {@link WebClientConfig} with light doubles for its collaborators.
   *
   * <p>The {@code test} profile is pinned so {@code connector(...)} takes the {@code
   * InsecureTrustManagerFactory} path and never reads an SSL bundle — which is what lets these
   * tests handshake against a local server with the committed test material and no setup.
   *
   * @param protocol the wire protocol under test
   * @param codec the encoding under test
   * @return a configuration whose beans can be built directly, with no Spring context
   */
  static WebClientConfig config(
      AppHttpProperties.BackendProtocol protocol, AppHttpProperties.BackendCodec codec) {
    ExchangeFilterFunction passthrough = (request, next) -> next.exchange(request);

    WebClientLoggingFilter logging = mock(WebClientLoggingFilter.class);
    when(logging.correlationIdPropagation()).thenReturn(passthrough);
    when(logging.callLogging()).thenReturn(passthrough);
    ActiveSquadronRelayFilter squadron = mock(ActiveSquadronRelayFilter.class);
    when(squadron.relayActiveSquadron()).thenReturn(passthrough);
    UserLocaleRelayFilter locale = mock(UserLocaleRelayFilter.class);
    when(locale.relayUserLocale()).thenReturn(passthrough);

    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});

    return new WebClientConfig(
        new AppBackendProperties("https://backend:11261"),
        new AppHttpProperties(
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            protocol,
            20,
            codec),
        logging,
        squadron,
        locale,
        new ClientIpRelayFilter(),
        environment,
        mock(SslBundles.class),
        ObservationRegistry.NOOP);
  }

  /**
   * Reads the negotiated ALPN protocol off whichever channel in the chain carries the TLS handler.
   *
   * <p>Under HTTP/2 a request is handled on a stream channel and the {@code SslHandler} lives on
   * its parent; under HTTP/1.1 both are the same channel. Walking up rather than branching on the
   * protocol keeps the reader out of the answer.
   *
   * @param channel the channel the request arrived on
   * @return the ALPN protocol, or {@code "none"} when the engine reports none
   */
  static String applicationProtocol(Channel channel) {
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
