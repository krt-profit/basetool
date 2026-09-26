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
 * Shared helpers for {@code WebClientConfig} tests: the configuration with its collaborator doubles
 * and the ALPN protocol reader.
 */
final class WebClientTestSupport {

  /** Not instantiable. */
  private WebClientTestSupport() {}

  /**
   * Builds the real {@link WebClientConfig} with light doubles for its collaborators, under the
   * {@code test} profile so the connector trusts any certificate.
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
            Duration.ofSeconds(120),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            protocol,
            20,
            codec,
            false),
        logging,
        squadron,
        locale,
        new ClientIpRelayFilter(),
        environment,
        mock(SslBundles.class),
        ObservationRegistry.NOOP);
  }

  /**
   * Reads the negotiated ALPN protocol from whichever channel in the parent chain carries the TLS
   * handler.
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
