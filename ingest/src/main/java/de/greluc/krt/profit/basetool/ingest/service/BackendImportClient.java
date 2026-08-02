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

package de.greluc.krt.profit.basetool.ingest.service;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractDto;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Relays an ingest call to the backend's existing import endpoints, carrying the caller's own
 * bearer token (the gateway never mints or elevates a token — it forwards the one it validated)
 * plus the {@code Accept-Language} and {@code X-Correlation-Id} headers (REQ-INGEST-001,
 * REQ-OBS-*). The backend does all matching and persists nothing (REQ-REFINERY-002); the gateway
 * only returns the draft JSON verbatim.
 *
 * <p>Each call runs through a Resilience4j circuit breaker (instance {@code backend}) so a backend
 * outage trips open quickly instead of piling up blocked threads. The breaker is configured to
 * ignore HTTP response errors (a 400 envelope reject is a client problem, not a backend-health
 * signal); only transport failures count toward opening it.
 */
@Slf4j
@Service
public class BackendImportClient {

  private static final String REFINERY_PATH = "/api/v1/refinery-orders/import-extract";
  private static final String BLUEPRINT_PREVIEW_PATH = "/api/v1/personal-blueprints/import/preview";

  private final WebClient backendWebClient;
  private final CircuitBreaker circuitBreaker;
  private final String correlationIdHeader;

  /**
   * Wires the backend {@link WebClient}, resolves the {@code backend} circuit breaker from the
   * Resilience4j registry, and captures the configured correlation-id header name so the outbound
   * relay uses the same header the gateway accepts inbound (REQ-OBS-002).
   *
   * @param backendWebClient the backend-facing client from {@code WebClientConfig}
   * @param circuitBreakerRegistry the auto-configured Resilience4j registry
   * @param loggingProperties supplies the correlation-id header name
   */
  public BackendImportClient(
      WebClient backendWebClient,
      CircuitBreakerRegistry circuitBreakerRegistry,
      LoggingProperties loggingProperties) {
    this.backendWebClient = backendWebClient;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("backend");
    this.correlationIdHeader = loggingProperties.correlationIdHeader();
    // Log the one-time OPEN/CLOSED transition at WARN so a backend outage leaves a breadcrumb in
    // the
    // ingest log (the frontend has ResilienceEventLogger; ingest had only ported the breaker). This
    // is the sanctioned health signal that pairs with the per-call DEBUG in GlobalExceptionHandler
    // and the resilience4j_circuitbreaker_state gauge (REQ-OBS-001).
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                log.warn(
                    "Backend circuit breaker {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
  }

  /**
   * Forwards a {@link RefineryExtractDto} as JSON to the backend refinery import endpoint and
   * returns the resulting draft JSON verbatim.
   *
   * @param bearer the caller's raw JWT (forwarded as {@code Authorization: Bearer})
   * @param acceptLanguage the caller's resolved locale, relayed for localized backend problems
   * @param correlationId the request correlation id, relayed for cross-module tracing
   * @param extract the validated extract payload
   * @return the backend's draft response body as JSON text
   */
  public @NotNull String forwardRefineryExtract(
      @NotNull String bearer,
      String acceptLanguage,
      String correlationId,
      @NotNull RefineryExtractDto extract) {
    return backendWebClient
        .post()
        .uri(REFINERY_PATH)
        .headers(commonHeaders(bearer, acceptLanguage, correlationId))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(extract)
        .retrieve()
        .bodyToMono(String.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .block();
  }

  /**
   * Forwards a blueprint export, already validated as a JSON object, to the backend's multipart
   * preview endpoint as a single {@code file} part (so the backend stays unchanged — it parses the
   * upload exactly as a manual file import would), and returns the preview JSON verbatim.
   *
   * @param bearer the caller's raw JWT (forwarded as {@code Authorization: Bearer})
   * @param acceptLanguage the caller's resolved locale, relayed for localized backend problems
   * @param correlationId the request correlation id, relayed for cross-module tracing
   * @param blueprintJson the blueprint export JSON bytes to upload as the {@code file} part
   * @return the backend's preview response body as JSON text
   */
  public @NotNull String forwardBlueprintPreview(
      @NotNull String bearer,
      String acceptLanguage,
      String correlationId,
      byte @NotNull [] blueprintJson) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder
        .part("file", new ByteArrayResource(blueprintJson))
        .filename("blueprints.json")
        .contentType(MediaType.APPLICATION_JSON);
    return backendWebClient
        .post()
        .uri(BLUEPRINT_PREVIEW_PATH)
        .headers(commonHeaders(bearer, acceptLanguage, correlationId))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(builder.build()))
        .retrieve()
        .bodyToMono(String.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .block();
  }

  /**
   * Builds the common outbound header customizer: the forwarded bearer plus the optional
   * locale/correlation relays. Never logs the token (REQ-OBS-*).
   *
   * @param bearer the caller's raw JWT
   * @param acceptLanguage the resolved locale, or {@code null} to omit the header
   * @param correlationId the correlation id, or {@code null} to omit the header
   * @return a header consumer applied to the outbound request
   */
  private @NotNull Consumer<HttpHeaders> commonHeaders(
      @NotNull String bearer, String acceptLanguage, String correlationId) {
    return headers -> {
      headers.setBearerAuth(bearer);
      if (acceptLanguage != null && !acceptLanguage.isBlank()) {
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
      }
      if (correlationId != null && !correlationId.isBlank()) {
        headers.set(correlationIdHeader, correlationId);
      }
    };
  }
}
