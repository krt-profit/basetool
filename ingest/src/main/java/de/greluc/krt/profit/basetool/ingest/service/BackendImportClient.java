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
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Relays an ingest call to the backend's existing import endpoints under the <em>gateway's own</em>
 * identity, naming the member it acts for in {@link #ON_BEHALF_OF_HEADER} (ADR-0129,
 * REQ-INGEST-001), plus the {@code Accept-Language} and {@code X-Correlation-Id} headers
 * (REQ-OBS-*). The backend does all matching and persists nothing (REQ-REFINERY-002); the gateway
 * only returns the draft JSON verbatim.
 *
 * <p>It used to forward the caller's own token instead. That made sender-constrained tokens
 * impossible — a DPoP-bound token is rejected outright by a resource server on the second hop,
 * which is what broke every send from 2026-08-03 — and it is why the caller's token no longer
 * travels through this class at all.
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

  /**
   * Names the member the gateway is acting for on the backend hop (ADR-0129, REQ-INGEST-001).
   *
   * <p>Since the gateway stopped relaying the caller's token, the backend can no longer read the
   * subject out of the bearer — that bearer now identifies the <em>gateway</em>. The subject
   * travels here instead, and the backend honours it only when the authenticated principal is the
   * gateway's service account. It carries a subject and nothing else: it cannot grant a role, widen
   * a scope or select an org unit, which is what keeps a header-borne identity from becoming an
   * escalation.
   *
   * <p>The backend declares the same literal; the two are kept in step by a parity test, because a
   * rename on one side alone degrades silently into "every ingest write is attributed to nobody"
   * rather than failing loudly.
   */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  /**
   * Upper bound on a relayed {@code Accept-Language}. A real header is a handful of language
   * ranges; anything longer is not content negotiation and is dropped rather than copied onto the
   * internal call.
   */
  private static final int MAX_ACCEPT_LANGUAGE_LENGTH = 100;

  /**
   * The character set an RFC 5646 language-range header can legitimately contain: tags, the {@code
   * *} wildcard, {@code q}-weights and their separators. Deliberately a whitelist — the point is to
   * exclude CR/LF and everything else that has no business in a header value, not to parse the
   * grammar. Every quantifier applies to a single disjoint character class, so the expression is
   * linear and cannot be driven into catastrophic backtracking by the very input it guards.
   */
  private static final Pattern ACCEPT_LANGUAGE_PATTERN = Pattern.compile("[A-Za-z0-9*,;=. _-]+");

  private final WebClient backendWebClient;
  private final ServiceAccountTokenProvider serviceAccountTokenProvider;
  private final CircuitBreaker circuitBreaker;
  private final String correlationIdHeader;
  private final String correlationIdMdcKey;

  /**
   * Wires the backend {@link WebClient}, resolves the {@code backend} circuit breaker from the
   * Resilience4j registry, and captures the configured correlation-id header name so the outbound
   * relay uses the same header the gateway accepts inbound (REQ-OBS-002).
   *
   * @param backendWebClient the backend-facing client from {@code WebClientConfig}
   * @param serviceAccountTokenProvider supplies the gateway's own token for the backend hop
   * @param circuitBreakerRegistry the auto-configured Resilience4j registry
   * @param loggingProperties supplies the correlation-id header name
   */
  public BackendImportClient(
      WebClient backendWebClient,
      ServiceAccountTokenProvider serviceAccountTokenProvider,
      CircuitBreakerRegistry circuitBreakerRegistry,
      LoggingProperties loggingProperties) {
    this.backendWebClient = backendWebClient;
    this.serviceAccountTokenProvider = serviceAccountTokenProvider;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("backend");
    this.correlationIdHeader = loggingProperties.correlationIdHeader();
    this.correlationIdMdcKey = loggingProperties.correlationIdMdcKey();
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
   * @param callerSub the authenticated caller the gateway acts for; relayed in {@link
   *     #ON_BEHALF_OF_HEADER}, never as a bearer
   * @param acceptLanguage the caller's locale, sanitized before it is relayed for localized backend
   *     problems
   * @param extract the validated extract payload
   * @return the backend's draft response body as JSON text
   */
  public @NotNull String forwardRefineryExtract(
      @NotNull String callerSub, String acceptLanguage, @NotNull RefineryExtractDto extract) {
    return backendWebClient
        .post()
        .uri(REFINERY_PATH)
        .headers(commonHeaders(callerSub, acceptLanguage))
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
    return backendWebClient
        .post()
        .uri(BLUEPRINT_PREVIEW_PATH)
        .headers(commonHeaders(callerSub, acceptLanguage))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(builder.build()))
        .retrieve()
        .bodyToMono(String.class)
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .block();
  }

  /**
   * Builds the common outbound header customizer: the gateway's own bearer and the caller it acts
   * for, plus the sanitized locale/correlation relays. Never logs either token (REQ-OBS-*).
   *
   * <p><b>Neither relayed header may come from the request unchanged.</b> The gateway is the only
   * internet-facing module, so anything it copies onto an internal call carries hostile input
   * across a trust boundary the backend was designed without — the backend is unreachable from the
   * internet and its other caller is the trusted frontend. The correlation id is therefore taken
   * from the MDC (already validated by {@link
   * de.greluc.krt.profit.basetool.ingest.filter.CorrelationIdFilter}) rather than from the inbound
   * header, and the locale is charset- and length-bounded here.
   *
   * <p>Sourcing the id from the MDC also fixes a correlation defect: the gateway logged the
   * sanitized id but forwarded the raw one, so a request whose inbound header failed validation was
   * logged under a fresh UUID in the gateway while the backend minted a <em>different</em> one —
   * the two modules' lines for one request could not be joined, precisely in the case worth tracing
   * (REQ-OBS-002).
   *
   * @param callerSub the authenticated caller the gateway is acting for
   * @param acceptLanguage the client-supplied locale, sanitized here; {@code null} omits the header
   * @return a header consumer applied to the outbound request
   */
  private @NotNull Consumer<HttpHeaders> commonHeaders(
      @NotNull String callerSub, String acceptLanguage) {
    String safeLanguage = sanitizedAcceptLanguage(acceptLanguage);
    String correlationId = MDC.get(correlationIdMdcKey);
    // Resolved here, once per relay, rather than captured earlier: the provider hands back a cached
    // token until shortly before expiry, so this is a field read in the common case and a grant
    // only
    // when the cache is cold.
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
   * Bounds a client-supplied {@code Accept-Language} to what an RFC 5646 header can legitimately
   * contain, so a hostile value cannot ride the relay into the internal call.
   *
   * <p>Rejects rather than trims on the first offending character: a header that is not a plain
   * language range is not a locale the backend should act on, and silently forwarding a repaired
   * prefix would hide the anomaly. Dropping it degrades to the backend's default locale, which is
   * the correct failure mode for a content-negotiation hint.
   *
   * @param acceptLanguage the raw inbound value, possibly {@code null}
   * @return the value when it is a well-formed, length-bounded language range, otherwise {@code
   *     null} to omit the header entirely
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
