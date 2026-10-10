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

package de.greluc.krt.profit.basetool.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.config.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.config.RestClientConfig;
import de.greluc.krt.profit.basetool.backend.config.UexProperties;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

/**
 * Pins the {@code @ConcurrencyLimit} on the external-integration clients: it is declared, enabled
 * by {@link AsyncConfig}, and bounds the calls in flight through the Spring proxy.
 */
class ExternalClientConcurrencyLimitTest {

  /** Both external clients declare their limit at class level. */
  @Test
  void bothClientsDeclareTheirLimit() {
    assertThat(UexClient.class.getAnnotation(ConcurrencyLimit.class).value())
        .isEqualTo(UexClient.MAX_CONCURRENT_CALLS)
        .isEqualTo(4);
    assertThat(ScWikiClient.class.getAnnotation(ConcurrencyLimit.class).value()).isEqualTo(2);
  }

  /**
   * Lombok copies the field's qualifier onto the generated constructor, so neither client falls
   * back to the unfiltered primary builder.
   */
  @Test
  void bothClientsAskForTheExternalBuilder() {
    for (Class<?> type : List.of(UexClient.class, ScWikiClient.class)) {
      Qualifier qualifier =
          type.getDeclaredConstructors()[0].getParameters()[0].getAnnotation(Qualifier.class);
      assertThat(qualifier).as(type.getSimpleName()).isNotNull();
      assertThat(qualifier.value()).isEqualTo(RestClientConfig.EXTERNAL_REST_CLIENT_BUILDER);
    }
  }

  /** The configuration that owns the executors switches the annotation on. */
  @Test
  void asyncConfigEnablesTheAnnotation() {
    assertThat(AsyncConfig.class.isAnnotationPresent(EnableResilientMethods.class)).isTrue();
  }

  /** Several threads calling one UEX client never get more calls in flight than the limit. */
  @Test
  void callsInFlightNeverExceedTheLimit() throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public @NotNull MockResponse dispatch(@NotNull RecordedRequest request)
                throws InterruptedException {
              int now = inFlight.incrementAndGet();
              peak.accumulateAndGet(now, Math::max);
              Thread.sleep(200);
              inFlight.decrementAndGet();
              return new MockResponse()
                  .setHeader("Content-Type", "application/json")
                  .setBody("{\"status\":\"ok\",\"data\":[]}");
            }
          });
      server.start();
      UexProperties properties =
          BoundProperties.bind(UexProperties.class, Map.of("api-url", server.url("/").toString()));
      try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
        context.registerBean("properties", UexProperties.class, () -> properties);
        context.register(Wiring.class);
        context.refresh();
        UexClient client = context.getBean(UexClient.class);

        int callers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
          done.add(
              pool.submit(
                  () -> {
                    start.await();
                    return client.getStarSystems();
                  }));
        }
        start.countDown();
        for (Future<?> future : done) {
          future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
      }
    }
    assertThat(peak.get()).isBetween(2, UexClient.MAX_CONCURRENT_CALLS);
  }

  /** Test wiring: the limit switched on, one real client over the unfiltered local builder. */
  @Configuration
  @EnableResilientMethods
  static class Wiring {

    @Bean(RestClientConfig.EXTERNAL_REST_CLIENT_BUILDER)
    RestClient.Builder externalRestClientBuilder() {
      return new RestClientConfig().restClientBuilder(ObservationRegistry.NOOP);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    UexClient uexClient(
        @Qualifier(RestClientConfig.EXTERNAL_REST_CLIENT_BUILDER) RestClient.Builder builder,
        UexProperties properties,
        MeterRegistry meterRegistry) {
      return new UexClient(builder, properties, meterRegistry);
    }
  }
}
