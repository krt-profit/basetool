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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The response-body cap that replaced the reactive codec's {@code maxInMemorySize}: a body within
 * the cap is read whole, one past it fails loudly instead of being truncated.
 */
class ResponseSizeLimitInterceptorTest {

  /** Cap used by the tests: small enough to cross with a short body. */
  private static final long CAP = 1024;

  /**
   * Builds an observed client against the mock server with the size cap installed.
   *
   * @param server the running mock server
   * @return the capped client
   */
  private static RestClient cappedClient(MockWebServer server) {
    return new RestClientConfig()
        .restClientBuilder(ObservationRegistry.NOOP)
        .baseUrl(server.url("/").toString())
        .requestInterceptor(new ResponseSizeLimitInterceptor(CAP))
        .build();
  }

  /**
   * A body exactly at the cap is delivered unchanged.
   *
   * @throws Exception if the mock server cannot be started
   */
  @Test
  void aBodyAtTheCapIsReadWhole() throws Exception {
    String body = "a".repeat((int) CAP);
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setBody(body));
      server.start();

      assertThat(cappedClient(server).get().uri("/x").retrieve().body(String.class))
          .isEqualTo(body);
    }
  }

  /**
   * One byte past the cap fails the read with a {@link RestClientException}, the type the catalogue
   * clients count and swallow — never a silently truncated body.
   *
   * @throws Exception if the mock server cannot be started
   */
  @Test
  void aBodyPastTheCapFailsInsteadOfBeingTruncated() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setBody("a".repeat((int) CAP + 1)));
      server.start();

      assertThatThrownBy(() -> cappedClient(server).get().uri("/x").retrieve().body(String.class))
          .isInstanceOf(RestClientException.class)
          .rootCause()
          .hasMessageContaining("exceeds the limit of " + CAP + " bytes");
    }
  }
}
