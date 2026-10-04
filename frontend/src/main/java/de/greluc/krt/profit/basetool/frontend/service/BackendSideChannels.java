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

package de.greluc.krt.profit.basetool.frontend.service;

import java.time.Duration;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * The two backend calls that bypass the resilience pass and {@link BackendErrorMapper}: the
 * notification SSE relay (REQ-NOTIF-010) and the live-sync subscribe probe (REQ-FE-015).
 *
 * <p>Part of the backend kernel with {@link BackendApiClient} (REQ-FE-029): no other class holds a
 * {@code WebClient}. Failures reach the caller as raised, because each caller decides on the raw
 * status itself.
 */
@Service
@RequiredArgsConstructor
public class BackendSideChannels {

  /** The backend notification stream the browser relay subscribes to. */
  private static final String NOTIFICATION_STREAM_URI = "/api/v1/notifications/stream";

  /** The body type of the backend notification stream. */
  private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
      new ParameterizedTypeReference<>() {};

  /** The streaming client without resilience, timeouts or OAuth2 filter. */
  private final WebClient sseWebClient;

  /** The probe client without resilience and OAuth2 filter. */
  private final WebClient liveSyncAuthWebClient;

  /**
   * Opens the caller's backend notification stream; nothing is sent until the flux is subscribed.
   *
   * @param bearerToken the access token, set as a plain {@code Authorization} header
   * @return the backend's events, erroring with the raw {@code WebClientResponseException} on a
   *     refusal
   */
  @NotNull
  public Flux<ServerSentEvent<String>> notificationStream(@NotNull String bearerToken) {
    return sseWebClient
        .get()
        .uri(NOTIFICATION_STREAM_URI)
        .headers(headers -> headers.setBearerAuth(bearerToken))
        .retrieve()
        .bodyToFlux(SSE_TYPE);
  }

  /**
   * Sends a GET probe and discards the body.
   *
   * @param uri the backend path, sent as-is
   * @param headers sets the captured bearer and org-unit pin
   * @param timeout how long to block for the answer
   * @throws org.springframework.web.reactive.function.client.WebClientResponseException on an error
   *     status
   * @throws IllegalStateException when {@code timeout} elapses first
   */
  public void probeStatus(
      @NotNull String uri, @NotNull Consumer<HttpHeaders> headers, @NotNull Duration timeout) {
    liveSyncAuthWebClient
        .get()
        .uri(uri)
        .headers(headers)
        .retrieve()
        .toBodilessEntity()
        .block(timeout);
  }

  /**
   * Sends a GET probe and decodes its body.
   *
   * @param path the backend path, sent as-is
   * @param headers sets the captured bearer and org-unit pin
   * @param bodyType the decoded body type
   * @param timeout how long to block for the answer
   * @param <T> the body type
   * @return the decoded body, or {@code null} when the backend returned none
   * @throws org.springframework.web.reactive.function.client.WebClientResponseException on an error
   *     status
   * @throws IllegalStateException when {@code timeout} elapses first
   */
  @Nullable
  public <T> T probeBody(
      @NotNull String path,
      @NotNull Consumer<HttpHeaders> headers,
      @NotNull ParameterizedTypeReference<T> bodyType,
      @NotNull Duration timeout) {
    return liveSyncAuthWebClient
        .get()
        .uri(path)
        .headers(headers)
        .retrieve()
        .bodyToMono(bodyType)
        .block(timeout);
  }
}
