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

package de.greluc.krt.profit.basetool.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the {@code Location} of a real Tomcat redirect: relative when the request carries no
 * forwarded headers ({@code server.tomcat.servlet.use-relative-redirects}), absolute on the edge's
 * public origin when it carries {@code X-Forwarded-*}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TomcatRedirectLocationTest {

  /** Where the SSO entry point sends an anonymous top-level navigation. */
  private static final String SILENT_SSO_PATH = "/oauth2/authorization/keycloak?prompt=none";

  @LocalServerPort private int port;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /**
   * Sends an anonymous top-level navigation to a protected page.
   *
   * @param forwardedHeaders header name and value pairs to add
   * @return the response, never followed
   * @throws IOException if the request fails to send
   * @throws InterruptedException if the send is interrupted
   */
  private HttpResponse<Void> navigate(String... forwardedHeaders)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/missions"))
            .header("Accept", "text/html")
            .header("Sec-Fetch-Mode", "navigate")
            .GET();
    if (forwardedHeaders.length > 0) {
      request.headers(forwardedHeaders);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.discarding());
  }

  @Test
  void aRedirectWithoutForwardedHeadersCarriesARelativeLocation() throws Exception {
    HttpResponse<Void> response = navigate();

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .as("Tomcat leaves a relative sendRedirect relative")
        .hasValue(SILENT_SSO_PATH);
  }

  @Test
  void aRedirectBehindTheEdgeCarriesTheAbsolutePublicLocation() throws Exception {
    HttpResponse<Void> response =
        navigate("X-Forwarded-Host", "basetool.example", "X-Forwarded-Proto", "https");

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .as("ForwardedHeaderFilter resolves the redirect against the public origin")
        .hasValue("https://basetool.example" + SILENT_SSO_PATH);
  }
}
