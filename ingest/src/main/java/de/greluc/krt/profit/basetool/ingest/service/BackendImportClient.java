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
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Relays an ingest call to the backend import endpoints under the gateway's own identity, naming
 * the member it acts for in {@link #ON_BEHALF_OF_HEADER} (ADR-0129), and returns the draft JSON
 * verbatim.
 *
 * <p>Each call runs through the {@code backend} circuit breaker via {@link
 * CircuitBreaker#executeSupplier} (ADR-0204); only transport failures, not HTTP error responses,
 * count toward opening it.
 */
@Slf4j
@Service
public class BackendImportClient {

  private static final String REFINERY_PATH = "/api/v1/refinery-orders/import-extract";
  private static final String BLUEPRINT_PREVIEW_PATH = "/api/v1/personal-blueprints/import/preview";

  /**
   * Header naming the member the gateway acts for on the backend hop (ADR-0129, REQ-INGEST-001).
   *
   * <p>The backend honours it only from the gateway's service account and evaluates the call with
   * the named member's roles and scope. The backend declares the same literal, kept in step by a
   * parity test.
   */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  /**
   * Upper bound on a relayed {@code Accept-Language}. A real header is a handful of language
   * ranges; anything longer is not content negotiation and is dropped rather than copied onto the
   * internal call.
   */
  private static final int MAX_ACCEPT_LANGUAGE_LENGTH = 100;

  /**
   * Whitelist of characters allowed in a relayed {@code Accept-Language} header; excludes CR/LF and
   * cannot backtrack catastrophically.
   */
  private static final Pattern ACCEPT_LANGUAGE_PATTERN = Pattern.compile("[A-Za-z0-9*,;=. _-]+");

  private final RestClient backendRestClient;
  private final ServiceAccountTokenProvider serviceAccountTokenProvider;
  private final CircuitBreaker circuitBreaker;
  private final String correlationIdHeader;
  private final String correlationIdMdcKey;

  /**
   * Wires the backend {@link RestClient}, the {@code backend} circuit breaker and the configured
   * correlation-id header name (REQ-OBS-002).
   *
   * @param backendRestClient the backend-facing client from {@code RestClientConfig}
   * @param serviceAccountTokenProvider supplies the gateway's own token for the backend hop
   * @param circuitBreakerRegistry the auto-configured Resilience4j registry
   * @param loggingProperties supplies the correlation-id header name
   */
  public BackendImportClient(
      @Qualifier("backendRestClient") @NotNull RestClient backendRestClient,
      @NotNull ServiceAccountTokenProvider serviceAccountTokenProvider,
      @NotNull CircuitBreakerRegistry circuitBreakerRegistry,
      @NotNull LoggingProperties loggingProperties) {
    this.backendRestClient = backendRestClient;
    this.serviceAccountTokenProvider = serviceAccountTokenProvider;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("backend");
    this.correlationIdHeader = loggingProperties.correlationIdHeader();
    this.correlationIdMdcKey = loggingProperties.correlationIdMdcKey();
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
   * @param callerSub the authenticated caller the gateway acts for; relayed in {@link
   *     #ON_BEHALF_OF_HEADER}, never as a bearer
   * @param acceptLanguage the caller's locale, sanitized before it is relayed for localized backend
   *     problems
   * @param extract the validated extract payload
   * @return the backend's draft response body as JSON text
   */
  public @NotNull String forwardRefineryExtract(
      @NotNull String callerSub, String acceptLanguage, @NotNull RefineryExtractDto extract) {
    Consumer<HttpHeaders> headers = commonHeaders(callerSub, acceptLanguage);
    return circuitBreaker.executeSupplier(
        () ->
            backendRestClient
                .post()
                .uri(REFINERY_PATH)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(extract)
                .retrieve()
                .body(String.class));
  }

  /**
   * Forwards a blueprint export to the backend's multipart preview endpoint as a single {@code
   * file} part and returns the preview JSON verbatim.
   *
   * @param callerSub the authenticated caller the gateway acts for; relayed in {@link
   *     #ON_BEHALF_OF_HEADER}, never as a bearer
   * @param acceptLanguage the caller's locale, sanitized before it is relayed for localized backend
   *     problems
   * @param blueprintJson the blueprint export JSON bytes to upload as the {@code file} part
   * @return the backend's preview response body as JSON text
   */
  public @NotNull String forwardBlueprintPreview(
      @NotNull String callerSub, String acceptLanguage, byte @NotNull [] blueprintJson) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder
        .part("file", new ByteArrayResource(blueprintJson))
        .filename("blueprints.json")
        .contentType(MediaType.APPLICATION_JSON);
    Consumer<HttpHeaders> headers = commonHeaders(callerSub, acceptLanguage);
    return circuitBreaker.executeSupplier(
        () ->
            backendRestClient
                .post()
                .uri(BLUEPRINT_PREVIEW_PATH)
                .headers(headers)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(String.class));
  }

  /**
   * Builds the outbound header customizer: the gateway's bearer, the on-behalf-of subject, the
   * sanitized locale and the correlation id from the MDC (REQ-OBS-002).
   *
   * <p>No relayed header is copied from the inbound request unchanged.
   *
   * @param callerSub the authenticated caller the gateway is acting for
   * @param acceptLanguage the client-supplied locale, sanitized here; {@code null} omits the header
   * @return a header consumer applied to the outbound request
   */
  private @NotNull Consumer<HttpHeaders> commonHeaders(
      @NotNull String callerSub, String acceptLanguage) {
    String safeLanguage = sanitizedAcceptLanguage(acceptLanguage);
    String correlationId = MDC.get(correlationIdMdcKey);
    String gatewayToken = serviceAccountTokenProvider.currentToken();
    return headers -> {
      headers.setBearerAuth(gatewayToken);
      headers.set(ON_BEHALF_OF_HEADER, callerSub);
      if (safeLanguage != null) {
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, safeLanguage);
      }
      if (correlationId != null && !correlationId.isBlank()) {
        headers.set(correlationIdHeader, correlationId);
      }
    };
  }

  /**
   * Validates a client-supplied {@code Accept-Language} against the RFC 5646 character set and a
   * length bound, dropping it entirely rather than repairing it.
   *
   * @param acceptLanguage the raw inbound value, possibly {@code null}
   * @return the value when it is a well-formed, length-bounded language range, otherwise {@code
   *     null} to omit the header
   */
  private static @Nullable String sanitizedAcceptLanguage(@Nullable String acceptLanguage) {
    if (acceptLanguage == null || acceptLanguage.isBlank()) {
      return null;
    }
    if (acceptLanguage.length() > MAX_ACCEPT_LANGUAGE_LENGTH) {
      return null;
    }
    return ACCEPT_LANGUAGE_PATTERN.matcher(acceptLanguage).matches() ? acceptLanguage : null;
  }
}
