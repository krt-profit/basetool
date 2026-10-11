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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.mockito.ArgumentMatchers.any;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mockito;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.web.reactive.function.client.WebClient;

/** Builds a real {@link BackendApiClient} over a test {@link WebClient}, for controller tests. */
public final class RealBackendApiClient {

  private RealBackendApiClient() {}

  /**
   * Wraps {@code webClient} in a real client with its own meter registry and no catalogue cache.
   *
   * @param webClient the client every call, authenticated or not, is sent through
   * @return the real client
   */
  public static BackendApiClient over(WebClient webClient) {
    return over(webClient, new SimpleMeterRegistry());
  }

  /**
   * A Mockito mock of {@link BackendApiClient} whose {@code execute} runs on a real client over
   * {@code webClient}, so a test can stub the typed verbs and still drive the multipart, binary or
   * bodiless call shapes against a mock server.
   *
   * @param webClient the client {@code execute} is sent through
   * @return the mock
   */
  public static BackendApiClient mockExecutingOver(WebClient webClient) {
    BackendApiClient real = over(webClient);
    BackendApiClient mock = Mockito.mock(BackendApiClient.class);
    Mockito.when(mock.execute(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                real.execute(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3)));
    return mock;
  }

  /**
   * Wraps {@code webClient} in a real client that counts its failures on {@code meterRegistry}.
   *
   * @param webClient the client every call, authenticated or not, is sent through
   * @param meterRegistry the registry {@code basetool_backend_client_errors_total} is counted on
   * @return the real client
   */
  public static BackendApiClient over(WebClient webClient, MeterRegistry meterRegistry) {
    return new BackendApiClient(webClient, webClient, meterRegistry, new NoOpCacheManager());
  }
}
