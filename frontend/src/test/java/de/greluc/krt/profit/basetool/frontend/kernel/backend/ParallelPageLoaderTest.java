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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.observability.LoggingProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ClientIpContext;
import de.greluc.krt.profit.basetool.frontend.kernel.web.CorrelationContext;
import io.micrometer.context.ContextRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit tests for {@link ParallelPageLoader}'s propagation of request-scoped context onto its
 * virtual-thread workers (REQ-FE-030): every accessor of its {@link ContextRegistry} — the four
 * relays and any other — plus the security context, and the headers a backend call made from a
 * worker therefore carries.
 */
class ParallelPageLoaderTest {

  /**
   * A holder no relay knows, standing in for any accessor a library or a later change registers.
   */
  private static final ThreadLocal<String> EXTRA = new ThreadLocal<>();

  private final ParallelPageLoader loader = new ParallelPageLoader(registry());

  @AfterEach
  void clearThreadLocals() {
    ClientIpContext.clear();
    ActiveSquadronContext.clear();
    CorrelationContext.clear();
    LocaleContextHolder.resetLocaleContext();
    SecurityContextHolder.clearContext();
    EXTRA.remove();
  }

  @Test
  void loadAsyncPropagatesClientIpToTheWorkerThread() {
    ClientIpContext.set("203.0.113.7");

    String seenOnWorker = loader.loadAsync(ClientIpContext::get).join();

    assertThat(seenOnWorker).isEqualTo("203.0.113.7");
  }

  @Test
  void loadAsyncPropagatesEveryRelayThreadLocalTogether() {
    UUID squadron = UUID.randomUUID();
    ActiveSquadronContext.set(squadron);
    CorrelationContext.set("corr-123");
    ClientIpContext.set("198.51.100.9");
    LocaleContextHolder.setLocale(Locale.ENGLISH);

    String[] seen =
        loader
            .loadAsync(
                () ->
                    new String[] {
                      String.valueOf(ActiveSquadronContext.get()),
                      CorrelationContext.get(),
                      ClientIpContext.get(),
                      LocaleContextHolder.getLocale().toLanguageTag()
                    })
            .join();

    assertThat(seen)
        .containsExactly(
            squadron.toString(), "corr-123", "198.51.100.9", Locale.ENGLISH.toLanguageTag());
  }

  @Test
  void loadAsyncPropagatesTheUserLocale() {
    LocaleContextHolder.setLocale(Locale.GERMANY);

    Locale seenOnWorker =
        loader
            .loadAsync(
                () ->
                    LocaleContextHolder.getLocaleContext() == null
                        ? null
                        : LocaleContextHolder.getLocaleContext().getLocale())
            .join();

    assertThat(seenOnWorker).isEqualTo(Locale.GERMANY);
  }

  @Test
  void loadAsyncPropagatesEveryRegisteredAccessorNotOnlyTheKnownOnes() {
    EXTRA.set("registered-later");

    assertThat(loader.loadAsync(EXTRA::get).join()).isEqualTo("registered-later");
  }

  @Test
  void loadAsyncPropagatesTheSecurityContext() {
    SecurityContextHolder.setContext(
        new SecurityContextImpl(new TestingAuthenticationToken("member", "n/a", "ROLE_MEMBER")));

    String seenOnWorker =
        loader
            .loadAsync(() -> SecurityContextHolder.getContext().getAuthentication().getName())
            .join();

    assertThat(seenOnWorker).isEqualTo("member");
  }

  @Test
  void loadAsyncWorkerSeesNullWhenCallerHasNoClientIp() {
    ClientIpContext.clear();

    String seenOnWorker = loader.loadAsync(ClientIpContext::get).join();

    assertThat(seenOnWorker).isNull();
  }

  @Test
  void loadAsyncLeavesTheCallingThreadClientIpUntouched() {
    ClientIpContext.set("192.0.2.42");

    loader.loadAsync(ClientIpContext::get).join();

    assertThat(ClientIpContext.get()).isEqualTo("192.0.2.42");
  }

  @Test
  void aBackendCallFromAParallelSectionCarriesTheLocaleCorrelationAndOrgUnit() throws Exception {
    try (MockWebServer backend = new MockWebServer()) {
      backend.enqueue(new MockResponse().setBody("{}"));
      backend.start();
      WebClient client = relayingClient(backend.url("/").toString());
      UUID orgUnit = UUID.randomUUID();
      LocaleContextHolder.setLocale(Locale.GERMANY);
      CorrelationContext.set("corr-parallel");
      ActiveSquadronContext.set(orgUnit);

      loader
          .loadAsync(
              () ->
                  client
                      .get()
                      .uri("/api/v1/section")
                      .retrieve()
                      .toBodilessEntity()
                      .block(Duration.ofSeconds(10)))
          .join();

      RecordedRequest request = backend.takeRequest(5, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.getHeader("Accept-Language")).isEqualTo("de-DE");
      assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("corr-parallel");
      assertThat(request.getHeader("X-Active-Org-Unit-Id")).isEqualTo(orgUnit.toString());
    }
  }

  /**
   * A registry like the production one: the library accessors, the four relays and one extra.
   *
   * @return the registry
   */
  private static ContextRegistry registry() {
    ContextRegistry registry = new ContextRegistry().loadThreadLocalAccessors();
    ReactorContextPropagationConfig.registerRelayAccessors(registry);
    registry.registerThreadLocalAccessor("test.extra", EXTRA::get, EXTRA::set, EXTRA::remove);
    return registry;
  }

  /**
   * A client with the frontend's real relay filters, which read the holders on the calling thread.
   *
   * @param baseUrl the stand-in backend
   * @return the client
   */
  private static WebClient relayingClient(String baseUrl) {
    WebClientLoggingFilter logging =
        new WebClientLoggingFilter(
            new LoggingProperties(
                "X-Correlation-Id", "correlationId", "userId", 2000, 1500, false));
    return WebClient.builder()
        .baseUrl(baseUrl)
        .filter(logging.correlationIdPropagation())
        .filter(new ActiveSquadronRelayFilter().relayActiveSquadron())
        .filter(new UserLocaleRelayFilter().relayUserLocale())
        .filter(new ClientIpRelayFilter().relayClientIp())
        .build();
  }
}
