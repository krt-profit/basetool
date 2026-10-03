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

package de.greluc.krt.profit.basetool.ingest.relay;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeGateFilter;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Relays an admitted exchange request to the backend's exchange layer as the gateway, naming the
 * member, the client, the relayed capabilities and the installation key (REQ-XCH-009, REQ-XCH-010),
 * and turns the backend's answer into what the exchange contract allows.
 *
 * <p>A backend refusal passes through only with a code of the exchange error registry and that
 * code's fixed detail, never the backend's own detail text (REQ-XCH-025); a code of the registry
 * gate passes only with the status the gateway's gate answers it with, so a client sees one code
 * and status whichever side refuses; the backend's generic codes are translated, everything else
 * becomes {@code 502 BACKEND_RELAY_FAILED}.
 *
 * <p>The relay has its own backend client, circuit breaker ({@value #BREAKER}) and, for large
 * change sets, bulkhead ({@value #LARGE_CHANGE_SETS}) (REQ-XCH-023).
 */
@Slf4j
@Service
public class ExchangeRelay {

  /**
   * The header naming the member the gateway acts for (ADR-0129).
   *
   * <p>The backend honours it only from the gateway's service account and declares the same
   * literal, kept in step by a parity test.
   */
  public static final String ON_BEHALF_OF_HEADER = "X-Ingest-On-Behalf-Of";

  /** The longest {@code Accept-Language} the relay passes on; a longer one is dropped. */
  private static final int MAX_ACCEPT_LANGUAGE_LENGTH = 100;

  /** The characters a relayed {@code Accept-Language} may hold; excludes CR and LF. */
  private static final Pattern ACCEPT_LANGUAGE_PATTERN = Pattern.compile("[A-Za-z0-9*,;=. _-]+");

  /** The header naming the relayed client. */
  public static final String CLIENT_HEADER = "X-Exchange-Client";

  /** The header listing the relayed capabilities, comma-separated. */
  public static final String CAPABILITIES_HEADER = "X-Exchange-Capabilities";

  /** The header carrying the installation's DPoP key thumbprint. */
  public static final String INSTALLATION_HEADER = "X-Exchange-Installation";

  /**
   * The header carrying, in epoch seconds, the connection time the gate compares with a client
   * revocation, so the backend can check it with the same time (REQ-XCH-008); absent when the token
   * lacks the claim.
   */
  public static final String CONNECTED_AT_HEADER = "X-Exchange-Connected-At";

  /** The code of a relay failure. */
  public static final String RELAY_FAILED = "BACKEND_RELAY_FAILED";

  /** The name of the exchange relay's circuit breaker. */
  public static final String BREAKER = "exchange";

  /** The name of the bulkhead that bounds how many large change sets are relayed at once. */
  public static final String LARGE_CHANGE_SETS = "exchangeLargeChangeSets";

  /** The seconds a client waits after {@code RELAY_BUSY}. */
  static final String BUSY_RETRY_AFTER_SECONDS = "10";

  /**
   * The ceiling on a backend answer the relay reads; the relay client's own cap, {@code
   * app.ingest.max-payload-bytes} (2 MiB by default), refuses a larger answer first, so this bound
   * applies only when that cap is configured above it.
   */
  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  /** Backend codes that mean the same as a registry code. */
  static final Map<String, String> TRANSLATED =
      Map.of(
          "ACCESS_DENIED",
          "NOT_PERMITTED",
          "VALIDATION_FAILED",
          "SCHEMA_INVALID",
          "BAD_REQUEST",
          "SCHEMA_INVALID",
          "OPTIMISTIC_LOCK",
          "VERSION_CONFLICT");

  /**
   * The codes of the gateway's registry gate, each with the only status it may arrive with: the
   * backend's exchange gate answers the same situation with the same code and status (REQ-XCH-025).
   */
  static final Map<String, Integer> GATE_STATUSES =
      Map.of(
          ExchangeRefusals.EXCHANGE_DISABLED,
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          ExchangeRefusals.REGISTRY_UNAVAILABLE,
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          ExchangeRefusals.CLIENT_NOT_ALLOWED,
          HttpStatus.FORBIDDEN.value(),
          ExchangeRefusals.CLIENT_SUSPENDED,
          HttpStatus.FORBIDDEN.value(),
          ExchangeRefusals.INSTALLATION_REVOKED,
          HttpStatus.UNAUTHORIZED.value(),
          ExchangeRefusals.CLIENT_REVOKED,
          HttpStatus.UNAUTHORIZED.value(),
          ExchangeRefusals.SCOPE_MISSING,
          HttpStatus.FORBIDDEN.value());

  /**
   * The registry codes a backend refusal may reach a client with, each with the fixed detail the
   * client sees in place of the backend's; a gate code carries the gateway gate's own detail.
   */
  static final Map<String, String> DETAILS =
      Map.ofEntries(
          Map.entry(
              "TERMS_NOT_ACCEPTED",
              "The member has not accepted the current terms of use; they accept them in the"
                  + " Basetool."),
          Map.entry("PENDING_APPROVAL", "The member's registration is still awaiting approval."),
          Map.entry("NO_ROLE", "The member holds no role in the Basetool."),
          Map.entry(
              "ACTING_MEMBER_REFUSED", "The Basetool refused the member this request acts for."),
          Map.entry("NOT_PERMITTED", "The member may not do this."),
          Map.entry("SCHEMA_INVALID", "The Basetool refused the request's content as malformed."),
          Map.entry(
              "VERSION_CONFLICT", "The entry changed since it was read; pull, merge and retry."),
          Map.entry(
              "CURSOR_EXPIRED",
              "The cursor is older than the retained changes; reconcile against a full snapshot."),
          Map.entry(
              "MASS_CHANGE_CONFIRMATION_REQUIRED",
              "The change set removes more than the mass-change guard allows without"
                  + " confirmation."),
          Map.entry(
              ExchangeRefusals.EXCHANGE_DISABLED, ExchangeGateFilter.EXCHANGE_DISABLED_DETAIL),
          Map.entry(
              ExchangeRefusals.REGISTRY_UNAVAILABLE,
              ExchangeGateFilter.REGISTRY_UNAVAILABLE_DETAIL),
          Map.entry(
              ExchangeRefusals.CLIENT_NOT_ALLOWED, ExchangeGateFilter.CLIENT_NOT_ALLOWED_DETAIL),
          Map.entry(ExchangeRefusals.CLIENT_SUSPENDED, ExchangeGateFilter.CLIENT_SUSPENDED_DETAIL),
          Map.entry(
              ExchangeRefusals.INSTALLATION_REVOKED,
              ExchangeGateFilter.INSTALLATION_REVOKED_DETAIL),
          Map.entry(ExchangeRefusals.CLIENT_REVOKED, ExchangeGateFilter.CLIENT_REVOKED_DETAIL),
          Map.entry(ExchangeRefusals.SCOPE_MISSING, ExchangeGateFilter.SCOPE_MISSING_DETAIL));

  /** Backend codes the exchange contract names and a client may see as they are. */
  static final Set<String> PASSED_THROUGH = DETAILS.keySet();

  /** Outcome: the backend answered 2xx and the answer was usable. */
  static final String OUTCOME_OK = "ok";

  /** Outcome: the backend refused with a code the client may see. */
  static final String OUTCOME_REFUSED = "refused";

  /** Outcome: the relay failed, answered {@code 502}. */
  static final String OUTCOME_FAILED = "failed";

  private final RestClient backendRestClient;
  private final ServiceAccountTokenProvider tokenProvider;
  private final CircuitBreaker circuitBreaker;
  private final Bulkhead largeChangeSets;
  private final ExchangeRefusals refusals;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final LoggingProperties loggingProperties;

  /**
   * Creates the relay on the exchange's own backend client, breaker and bulkhead.
   *
   * @param exchangeRestClient the exchange relay's backend client
   * @param tokenProvider the gateway's own backend identity
   * @param circuitBreakerRegistry supplies the {@value #BREAKER} breaker
   * @param bulkheadRegistry supplies the {@value #LARGE_CHANGE_SETS} bulkhead
   * @param refusals counts a large change set refused for want of a slot
   * @param objectMapper parses the backend's answers
   * @param meterRegistry counts the outcomes
   * @param loggingProperties names the correlation header and MDC key
   */
  public ExchangeRelay(
      @Qualifier("exchangeRestClient") @NotNull RestClient exchangeRestClient,
      @NotNull ServiceAccountTokenProvider tokenProvider,
      @NotNull CircuitBreakerRegistry circuitBreakerRegistry,
      @NotNull BulkheadRegistry bulkheadRegistry,
      @NotNull ExchangeRefusals refusals,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry,
      @NotNull LoggingProperties loggingProperties) {
    this.backendRestClient = exchangeRestClient;
    this.tokenProvider = tokenProvider;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(BREAKER);
    this.largeChangeSets = bulkheadRegistry.bulkhead(LARGE_CHANGE_SETS);
    this.refusals = refusals;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.loggingProperties = loggingProperties;
  }

  /** Registers the outcome counter at zero. */
  @PostConstruct
  void register() {
    for (String outcome : new String[] {OUTCOME_OK, OUTCOME_REFUSED, OUTCOME_FAILED}) {
      meterRegistry.counter(
          MetricNames.EXCHANGE_RELAY,
          MetricNames.TAG_OUTCOME,
          outcome,
          MetricNames.TAG_CLIENT_ID,
          MetricNames.EXCHANGE_CLIENT_NONE);
    }
  }

  /**
   * Relays one request.
   *
   * @param method the method
   * @param backendPath the backend path, e.g. {@code /api/v1/exchange/catalog/locations}
   * @param body the JSON body, or {@code null} for none
   * @param context what the gate established
   * @param acceptLanguage the caller's {@code Accept-Language}, or {@code null}
   * @return the backend's answer as the exchange contract allows it
   */
  public @NotNull Result forward(
      @NotNull HttpMethod method,
      @NotNull String backendPath,
      @Nullable JsonNode body,
      @NotNull ExchangeRequestContext context,
      @Nullable String acceptLanguage) {
    Raw raw;
    try {
      raw = call(method, backendPath, body, context, acceptLanguage);
    } catch (RestClientException
        | CallNotPermittedException
        | ServiceAccountTokenProvider.ServiceAccountTokenException e) {
      log.warn(
          "Exchange relay to {} could not reach the backend: {}",
          backendPath,
          e.getClass().getSimpleName());
      count(OUTCOME_FAILED, context.clientId());
      return Result.failed();
    }
    return interpret(raw, backendPath, context.clientId());
  }

  /**
   * Relays one large change set within the {@value #LARGE_CHANGE_SETS} bulkhead; when every slot is
   * taken the set is not relayed but refused and counted.
   *
   * @param method the method
   * @param backendPath the backend path
   * @param body the change set
   * @param context what the gate established
   * @param acceptLanguage the caller's {@code Accept-Language}, or {@code null}
   * @return the backend's answer as the exchange contract allows it, or {@code 503 RELAY_BUSY}
   */
  public @NotNull Result forwardLarge(
      @NotNull HttpMethod method,
      @NotNull String backendPath,
      @NotNull JsonNode body,
      @NotNull ExchangeRequestContext context,
      @Nullable String acceptLanguage) {
    if (!largeChangeSets.tryAcquirePermission()) {
      refusals.count(ExchangeRefusals.RELAY_BUSY, context.clientId());
      return Result.busy();
    }
    try {
      return forward(method, backendPath, body, context, acceptLanguage);
    } finally {
      largeChangeSets.onComplete();
    }
  }

  /**
   * Sends one request to the backend through the circuit breaker.
   *
   * @param method the method
   * @param backendPath the backend path
   * @param body the JSON body, or {@code null} for none
   * @param context what the gate established
   * @param acceptLanguage the caller's {@code Accept-Language}, or {@code null}
   * @return the backend's raw answer
   * @throws RestClientException if the backend cannot be reached or its answer read
   * @throws CallNotPermittedException if the circuit breaker is open
   * @throws ServiceAccountTokenProvider.ServiceAccountTokenException if the gateway has no token
   */
  private @NotNull Raw call(
      @NotNull HttpMethod method,
      @NotNull String backendPath,
      @Nullable JsonNode body,
      @NotNull ExchangeRequestContext context,
      @Nullable String acceptLanguage) {
    String token = tokenProvider.currentToken();
    String correlationId = MDC.get(loggingProperties.correlationIdMdcKey());
    String language = sanitizedAcceptLanguage(acceptLanguage);
    return circuitBreaker.executeSupplier(
        () -> {
          RestClient.RequestBodySpec request =
              backendRestClient
                  .method(method)
                  .uri(backendPath)
                  .headers(
                      headers -> {
                        headers.setBearerAuth(token);
                        headers.set(ON_BEHALF_OF_HEADER, context.member());
                        headers.set(CLIENT_HEADER, context.clientId());
                        headers.set(
                            CAPABILITIES_HEADER,
                            String.join(",", new TreeSet<>(context.capabilities())));
                        headers.set(INSTALLATION_HEADER, context.keyThumbprint());
                        if (context.connectedAt() != null) {
                          headers.set(CONNECTED_AT_HEADER, Long.toString(context.connectedAt()));
                        }
                        headers.setAccept(
                            List.of(
                                MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
                        if (language != null) {
                          headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
                        }
                        if (correlationId != null && !correlationId.isBlank()) {
                          headers.set(loggingProperties.correlationIdHeader(), correlationId);
                        }
                      });
          if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
          }
          return request.exchange(
              (req, res) -> new Raw(res.getStatusCode().value(), read(res.getBody())));
        });
  }

  /**
   * Checks a client-supplied {@code Accept-Language} against the RFC 5646 characters and a length
   * bound, dropping it rather than repairing it.
   *
   * @param acceptLanguage the inbound value, or {@code null}
   * @return the value when it is well-formed and short enough, otherwise {@code null}
   */
  public static @Nullable String sanitizedAcceptLanguage(@Nullable String acceptLanguage) {
    if (acceptLanguage == null
        || acceptLanguage.isBlank()
        || acceptLanguage.length() > MAX_ACCEPT_LANGUAGE_LENGTH) {
      return null;
    }
    return ACCEPT_LANGUAGE_PATTERN.matcher(acceptLanguage).matches() ? acceptLanguage : null;
  }

  /**
   * Turns the backend's raw answer into a result.
   *
   * <p>A {@code 401} or {@code 403} without a code the client may see refuses the gateway's own
   * identity (ADR-0129), so the cached service-account token is dropped and the next relay mints a
   * fresh one; a refusal with such a code concerns the member or the client and keeps it.
   *
   * @param raw the answer
   * @param backendPath the backend path, for the log
   * @param client the admitted request's registry client id, the counter's {@code client_id}
   * @return the result
   */
  @NotNull
  Result interpret(@NotNull Raw raw, @NotNull String backendPath, @NotNull String client) {
    JsonNode node = parse(raw.body());
    if (raw.status() >= 200 && raw.status() < 300 && node != null) {
      count(OUTCOME_OK, client);
      return Result.ok(node);
    }
    if (raw.status() >= 400 && raw.status() < 600 && node != null && node.isObject()) {
      JsonNode code = node.get("code");
      String backendCode = code != null && code.isString() ? code.stringValue() : null;
      String exchangeCode =
          backendCode == null ? null : TRANSLATED.getOrDefault(backendCode, backendCode);
      String detail = exchangeCode == null ? null : DETAILS.get(exchangeCode);
      Integer gateStatus = exchangeCode == null ? null : GATE_STATUSES.get(exchangeCode);
      boolean statusFits =
          gateStatus == null ? raw.status() < 500 : gateStatus.intValue() == raw.status();
      if (detail != null && statusFits) {
        count(OUTCOME_REFUSED, client);
        return Result.refused(raw.status(), exchangeCode, detail);
      }
    }
    log.warn(
        "Exchange relay to {} failed: status={} usableBody={}",
        backendPath,
        raw.status(),
        node != null);
    if (raw.status() == HttpStatus.UNAUTHORIZED.value()
        || raw.status() == HttpStatus.FORBIDDEN.value()) {
      log.warn(
          "The backend refused the gateway's own identity with {}; dropping the cached"
              + " service-account token",
          raw.status());
      tokenProvider.invalidate();
    }
    count(OUTCOME_FAILED, client);
    return Result.failed();
  }

  /**
   * Tells whether a code is one the gateway's registry gate answers, so a refusal of the backend's
   * gate with it means the same as the gateway's own.
   *
   * @param code a problem code, or {@code null}
   * @return {@code true} for a code of the registry gate
   */
  public static boolean isGateCode(@Nullable String code) {
    return code != null && GATE_STATUSES.containsKey(code);
  }

  /**
   * Returns the {@code Retry-After} a relay result carries: for a relayed refusal the same the
   * gateway's gate sends with that code, for {@code RELAY_BUSY} {@value #BUSY_RETRY_AFTER_SECONDS}.
   *
   * @param code a problem code, or {@code null}
   * @return the seconds, or {@code null} when the code carries none
   */
  public static @Nullable String retryAfterSeconds(@Nullable String code) {
    if (ExchangeRefusals.RELAY_BUSY.equals(code)) {
      return BUSY_RETRY_AFTER_SECONDS;
    }
    return ExchangeRefusals.EXCHANGE_DISABLED.equals(code)
            || ExchangeRefusals.REGISTRY_UNAVAILABLE.equals(code)
        ? ExchangeGateFilter.RETRY_AFTER_SECONDS
        : null;
  }

  /**
   * Counts one outcome.
   *
   * @param outcome the outcome
   * @param client the admitted request's registry client id
   */
  private void count(@NotNull String outcome, @NotNull String client) {
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_RELAY,
            MetricNames.TAG_OUTCOME,
            outcome,
            MetricNames.TAG_CLIENT_ID,
            client)
        .increment();
  }

  /**
   * Reads a bounded answer body.
   *
   * @param in the body stream
   * @return the bytes
   * @throws IOException if reading fails or the body is too large
   */
  private static byte @NotNull [] read(@NotNull InputStream in) throws IOException {
    byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
    if (bytes.length > MAX_RESPONSE_BYTES) {
      throw new IOException("The backend answer exceeds " + MAX_RESPONSE_BYTES + " bytes");
    }
    return bytes;
  }

  /**
   * Parses an answer body.
   *
   * @param body the bytes
   * @return the JSON, or {@code null} when it is empty or not JSON
   */
  private @Nullable JsonNode parse(byte @NotNull [] body) {
    if (body.length == 0) {
      return null;
    }
    try {
      return objectMapper.readTree(body);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /**
   * The backend's raw answer.
   *
   * @param status the status
   * @param body the body
   */
  record Raw(int status, byte @NotNull [] body) {}

  /**
   * What the relay produced.
   *
   * @param status the status to answer with
   * @param body the answer on success, or {@code null}
   * @param code the problem code on a refusal or failure, or {@code null}
   * @param detail the problem detail, or {@code null}
   */
  public record Result(
      int status, @Nullable JsonNode body, @Nullable String code, @Nullable String detail) {

    /**
     * A usable answer.
     *
     * @param body the answer
     * @return the result
     */
    static @NotNull Result ok(@NotNull JsonNode body) {
      return new Result(HttpStatus.OK.value(), body, null, null);
    }

    /**
     * A refusal the client may see.
     *
     * @param status the backend's status
     * @param code the registry code
     * @param detail the code's fixed detail
     * @return the result
     */
    static @NotNull Result refused(int status, @NotNull String code, @NotNull String detail) {
      return new Result(status, null, code, detail);
    }

    /**
     * A relay failure.
     *
     * @return the result
     */
    public static @NotNull Result failed() {
      return new Result(
          HttpStatus.BAD_GATEWAY.value(),
          null,
          RELAY_FAILED,
          "The Basetool did not answer usably; try again later.");
    }

    /**
     * A large change set refused because the gateway already relays as many as it admits at once.
     *
     * @return the result
     */
    public static @NotNull Result busy() {
      return new Result(
          HttpStatus.SERVICE_UNAVAILABLE.value(),
          null,
          ExchangeRefusals.RELAY_BUSY,
          "The gateway is relaying as many large change sets as it admits at once; retry after"
              + " Retry-After or send smaller change sets.");
    }

    /**
     * Whether the relay produced a usable answer.
     *
     * @return {@code true} for a 2xx answer
     */
    public boolean isOk() {
      return body != null;
    }
  }
}
