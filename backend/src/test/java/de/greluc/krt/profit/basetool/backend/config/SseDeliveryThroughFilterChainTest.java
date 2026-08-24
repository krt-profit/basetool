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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.service.CustomJwtGrantedAuthoritiesConverter;
import de.greluc.krt.profit.basetool.backend.service.TermsAcceptanceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The end-to-end guard for #1653: bytes written into an {@code SseEmitter} must reach a socket.
 *
 * <p>{@link StreamAwareShallowEtagHeaderFilterTest} pins the one filter that broke this, which is
 * the narrower half of the guard and would not have caught the defect had it come from a different
 * filter. This one runs the request through the <em>real</em> chain on a real port and waits for a
 * byte, so any future component that buffers, wraps or delays a streaming response fails here
 * regardless of which one it is.
 *
 * <p>That distinction is the whole reason this test exists: the original defect was invisible to
 * every server-side signal we had. The emitter accepted the write, the delivery counter
 * incremented, {@code basetool_sse_connections} was healthy, and the client on the other end of the
 * connection received nothing for as long as it held it. Only reading the socket separates the two
 * states, so only a test that reads a socket can pin them apart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SseDeliveryThroughFilterChainTest {

  /** How long a first byte may take before the stream counts as swallowed. */
  private static final Duration FIRST_BYTE_BUDGET = Duration.ofSeconds(15);

  /** The application connector the test opens its streams against. */
  @Value("${local.server.port}")
  private int port;

  /** Stubbed so a bare {@code Bearer} string authenticates as one fixed member. */
  @MockitoBean private JwtDecoder jwtDecoder;

  /**
   * Stubbed past the terms gate. The subject here is a token, not a seeded member, so the real
   * check refuses it with a {@code 403} before the request ever reaches a filter that could buffer
   * it — which is the property under test. The concrete service is overridden rather than its
   * {@code TermsConsentCheck} interface, because the same bean satisfies both and replacing it
   * under the narrower type leaves the admin controller without its dependency.
   */
  @MockitoBean private TermsAcceptanceService termsAcceptanceService;

  /**
   * Stubbed past the approval gate for the same reason: the real converter reads the member out of
   * the database and, finding none, grants the lone {@code ROLE_PENDING_APPROVAL} that every {@code
   * /api/**} call is refused with. Authorization is not what this test is about — a swallowed
   * stream swallows an authorised caller's bytes just as thoroughly.
   */
  @MockitoBean private CustomJwtGrantedAuthoritiesConverter authoritiesConverter;

  // HTTP/1.1 explicitly, for the reason ManagementPortIsolationTest gives: the JDK client's HTTP/2
  // stream handling can RST_STREAM against a Tomcat still warming up under full-suite load.
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /** The subject every request in this class authenticates as. */
  private final UUID subject = UUID.randomUUID();

  /** Points the stubbed decoder at a minimal token carrying only the subject claim. */
  @BeforeEach
  void stubToken() {
    Instant now = Instant.now();
    Jwt jwt =
        new Jwt(
            "test-token",
            now,
            now.plusSeconds(300),
            Map.of("alg", "none"),
            Map.of("sub", subject.toString()));
    when(jwtDecoder.decode(anyString())).thenReturn(jwt);
    when(termsAcceptanceService.hasAcceptedCurrentTerms(any())).thenReturn(true);
    when(authoritiesConverter.convert(any()))
        .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("the notification stream delivers its first event to a real socket")
  void notificationStreamDeliversToASocket() throws Exception {
    assertThat(firstLineOf("/api/v1/notifications/stream"))
        // The service sends `connected` the moment it registers the emitter, so the very first
        // frame is already proof. Waiting for a heartbeat instead would cost 20 s per run and test
        // the same property.
        .as("first frame of the notification stream")
        .contains("connected");
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  @DisplayName("the live-sync stream delivers its first event to a real socket")
  void liveSyncStreamDeliversToASocket() throws Exception {
    assertThat(firstLineOf("/api/v1/live-sync/stream?topics=inventory"))
        .as("first frame of the live-sync stream")
        .contains("subscribed");
  }

  /**
   * Opens a stream and returns everything that arrived up to and including its first non-blank
   * line, giving up after {@link #FIRST_BYTE_BUDGET}.
   *
   * <p>The response is consumed as a line stream rather than a string: a string body handler waits
   * for the end of a body that, by design, has none.
   *
   * @param path the stream path including any query, leading slash included.
   * @return the first non-blank line of the body.
   * @throws Exception if the request fails, or if no line arrives within the budget.
   */
  private String firstLineOf(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer test-token")
            .header("Accept", "text/event-stream")
            .GET()
            .build();

    HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
    if (response.statusCode() != 200) {
      // The body is read into the message deliberately: every refusal on this path is an RFC 7807
      // document naming the gate, and a bare status code sends the next reader hunting for which
      // of the four filters in front of the stream said no.
      throw new AssertionError(
          "status "
              + response.statusCode()
              + " for "
              + path
              + ", body "
              + response.body().toList());
    }

    // The read has to be interruptible: a swallowed stream blocks forever rather than failing, so
    // an ordinary read on this thread would hang the build instead of reporting the defect.
    CompletableFuture<List<String>> firstLine =
        CompletableFuture.supplyAsync(
            () -> response.body().filter(line -> !line.isBlank()).limit(1).toList());
    try {
      List<String> lines = firstLine.get(FIRST_BYTE_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(lines).as("a line arrived from %s", path).isNotEmpty();
      return lines.getFirst();
    } finally {
      firstLine.cancel(true);
    }
  }
}
