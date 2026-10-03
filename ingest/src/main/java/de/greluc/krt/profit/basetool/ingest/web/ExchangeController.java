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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeGatewayProperties;
import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeSchemas;
import de.greluc.krt.profit.basetool.ingest.exchange.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The authenticated exchange routes built so far (REQ-XCH-001). Each request has passed both
 * exchange gates; a body is checked against its v1 schema before the relay, undeclared fields are
 * reported as {@code UNKNOWN_FIELD} warnings where the answer carries warnings, and the backend's
 * answer is checked against its schema before it reaches the client. The committed exchange
 * document describes these routes.
 */
@Slf4j
@RestController
@RequestMapping("/exchange/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangeController {

  /** The API version the service document reports. */
  static final String API_VERSION = "1.0";

  /** The largest change set, as the contract fixes it. */
  static final int BATCH_MAX_OPS = 500;

  /**
   * The most ops a change set may hold and still be relayed outside the large-set bulkhead
   * (REQ-XCH-023).
   */
  static final int LARGE_CHANGE_SET_OPS = 100;

  /** The warning code of an undeclared field. */
  static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";

  /** The largest page, as the contract fixes it. */
  static final int PAGE_MAX_LIMIT = 1000;

  /** The code of a cursor the server can no longer serve. */
  static final String CURSOR_EXPIRED = "CURSOR_EXPIRED";

  /** The code of a change set above the contract's size. */
  static final String BATCH_TOO_LARGE = "BATCH_TOO_LARGE";

  /** The code of a draft too large to hand off. */
  static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

  /** The code of a change set the mass-change guard held back for the member's confirmation. */
  static final String MASS_CHANGE_CONFIRMATION_REQUIRED = "MASS_CHANGE_CONFIRMATION_REQUIRED";

  /** The frontend page where the member confirms a staged mass change. */
  static final String CONFIRMATION_PATH = "/connected-apps/confirm";

  /** The only offline-file major version the gateway reads. */
  static final String FORMAT_MAJOR = "1";

  /** The envelope field that names the offline-file format version. */
  static final String FORMAT_VERSION = "formatVersion";

  /** The violation message of an envelope of another major version. */
  static final String UNSUPPORTED_MAJOR = "unsupported major version";

  /** Seconds a client waits after the staging store failed. */
  private static final long RETRY_AFTER_SECONDS = 60;

  /** The detail of a staging the byte budget refused. */
  private static final String BUDGET_EXHAUSTED_DETAIL =
      "The exchange's storage budget is full; try again later.";

  private static final String BACKEND = "/api/v1/exchange";

  private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,4}$");

  private final ExchangeRelay relay;
  private final ExchangeSchemas schemas;
  private final ExchangeGatewayProperties properties;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final HandoffStagingService stagingService;
  private final ExchangeBudget budget;
  private final ExchangeQuotas quotas;
  private final ExchangeStoreProperties storeProperties;
  private final IngestProperties ingestProperties;
  private final MeterRegistry meterRegistry;

  /** Registers the staged mass-change counter at zero. */
  @PostConstruct
  void registerCounters() {
    meterRegistry.counter(
        MetricNames.EXCHANGE_MASS_CHANGES_STAGED,
        MetricNames.TAG_CLIENT_ID,
        MetricNames.EXCHANGE_CLIENT_NONE);
  }

  /**
   * Returns the service document: what this token may do and what the server expects.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the document, or the refusal when the backend's exchange gate refuses the request with
   *     a code of the registry gate
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> serviceDocument(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    ObjectNode document = objectMapper.createObjectNode();
    document.put("apiVersion", API_VERSION);
    ArrayNode capabilities = document.putArray("capabilities");
    new TreeSet<>(context.capabilities()).forEach(capabilities::add);
    ExchangeRelay.Result installation =
        relay.forward(HttpMethod.GET, BACKEND + "/me/installation", null, context, acceptLanguage);
    if (ExchangeRelay.isGateCode(installation.code())) {
      return problem(installation.status(), installation.code(), installation.detail());
    }
    if (installation.isOk() && installation.body().get("installationId") != null) {
      document.set("installationId", installation.body().get("installationId"));
    }
    ObjectNode limits = document.putObject("limits");
    limits.put("batchMaxOps", BATCH_MAX_OPS);
    if (context.client().requestsPerMinute() != null) {
      limits.put("requestsPerMinute", context.client().requestsPerMinute());
    }
    if (context.client().writesPerDay() != null) {
      limits.put("writesPerDay", context.client().writesPerDay());
    }
    document.putArray("deprecations");
    document.put("docsUrl", properties.docsUrl());
    document.put("minClientVersion", context.client().minClientVersion());
    return answer(document, "service-document.schema.json");
  }

  /**
   * Labels the calling installation.
   *
   * @param body the label
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the labelled installation
   */
  @PostMapping(value = "/me/installation", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> installation(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return relayBody(
        body,
        "installation.schema.json",
        "/me/installation",
        "installation.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Answers whether an RSI handle belongs to the member, never disclosing the stored one.
   *
   * @param body the handle
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return {@code match}, {@code mismatch} or {@code unknown}
   */
  @PostMapping(value = "/me/account-check", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> accountCheck(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return relayBody(
        body,
        "account-check-request.schema.json",
        "/me/account-check",
        "account-check-response.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Resolves item references.
   *
   * @param body the references
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return one result per reference
   */
  @PostMapping(value = "/catalog/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> resolve(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return relayBody(
        body,
        "resolve-request.schema.json",
        "/catalog/resolve",
        "resolve-response.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Lists the Lager's non-hidden locations.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the locations
   */
  @GetMapping("/catalog/locations")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> locations(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    return relayed(
        relay.forward(
            HttpMethod.GET, BACKEND + "/catalog/locations", null, context, acceptLanguage),
        "location-list.schema.json",
        List.of());
  }

  /**
   * Returns a snapshot page of the member's blueprints, or with {@code cursor} the changes since
   * it.
   *
   * @param cursor the cursor of the last page, or {@code null} for a new snapshot
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping("/me/blueprints")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> blueprints(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return page(
        "/me/blueprints",
        "page.schema.json#/$defs/blueprintPage",
        cursor,
        limit,
        request,
        acceptLanguage);
  }

  /**
   * Returns a snapshot page of the member's personal stock lots, or with {@code cursor} the changes
   * since it.
   *
   * @param cursor the cursor of the last page, or {@code null} for a new snapshot
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping("/me/stock")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> stock(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return page(
        "/me/stock", "page.schema.json#/$defs/stockPage", cursor, limit, request, acceptLanguage);
  }

  /**
   * Returns a snapshot page of the member's ships, or with {@code cursor} the changes since it.
   *
   * @param cursor the cursor of the last page, or {@code null} for a new snapshot
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping("/me/ships")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> ships(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return page(
        "/me/ships", "page.schema.json#/$defs/shipPage", cursor, limit, request, acceptLanguage);
  }

  /**
   * Returns the anonymised open demand of the units the member belongs to.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the demand or a problem
   */
  @GetMapping("/me/org-demand")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> orgDemand(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    return relayed(
        relay.forward(HttpMethod.GET, BACKEND + "/me/org-demand", null, context, acceptLanguage),
        "org-demand.schema.json",
        List.of());
  }

  /**
   * Adds or removes blueprints.
   *
   * @param body the change set
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the change result or a problem
   */
  @PostMapping(value = "/me/blueprints/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> blueprintChanges(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return changes("blueprints", "blueprintChangeSet", body, request, acceptLanguage);
  }

  /**
   * Sets the quantities of stock lots.
   *
   * @param body the change set
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the change result or a problem
   */
  @PostMapping(value = "/me/stock/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> stockChanges(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return changes("stock", "stockChangeSet", body, request, acceptLanguage);
  }

  /**
   * Links, creates, updates or removes ships.
   *
   * @param body the change set
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the change result or a problem
   */
  @PostMapping(value = "/me/ships/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> shipChanges(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return changes("ships", "shipChangeSet", body, request, acceptLanguage);
  }

  /**
   * Stages blueprints for the member's review in the browser; nothing is written until the member
   * confirms.
   *
   * @param body the {@code basetool.blueprints} envelope
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the handoff or a problem
   */
  @PostMapping(value = "/me/drafts/blueprints", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> blueprintDraft(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return draft(
        "blueprints",
        "blueprint-draft.schema.json",
        HandoffKind.BLUEPRINT,
        ingestProperties.blueprintPath(),
        body,
        request,
        acceptLanguage);
  }

  /**
   * Stages refinery orders for the member's review in the browser; nothing is written until the
   * member confirms.
   *
   * @param body the refinery extract
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the handoff or a problem
   */
  @PostMapping(value = "/me/drafts/refinery-orders", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> refineryDraft(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return draft(
        "refinery-orders",
        "refinery-draft.schema.json",
        HandoffKind.REFINERY,
        ingestProperties.refineryPath(),
        body,
        request,
        acceptLanguage);
  }

  /**
   * Checks a draft, relays it to the backend's preview, stages the answer for the member's review
   * and answers where the member opens it.
   *
   * @param resource the draft's path segment
   * @param schema the draft's schema
   * @param kind the handoff's kind
   * @param path the frontend page that opens the handoff
   * @param body the draft
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the draft result or a problem
   */
  private @NotNull ResponseEntity<?> draft(
      @NotNull String resource,
      @NotNull String schema,
      @NotNull HandoffKind kind,
      @NotNull String path,
      @NotNull JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    List<ExchangeSchemas.Violation> violations = schemas.validate(schema, body);
    if (!violations.isEmpty()) {
      return schemaInvalid(violations);
    }
    ExchangeSchemas.Violation unsupported =
        kind == HandoffKind.BLUEPRINT ? unsupportedFormatMajor(body) : null;
    if (unsupported != null) {
      return schemaInvalid(List.of(unsupported));
    }
    ExchangeRelay.Result result =
        relay.forward(
            HttpMethod.POST, BACKEND + "/me/drafts/" + resource, body, context, acceptLanguage);
    if (!result.isOk()) {
      return problem(result.status(), result.code(), result.detail());
    }
    String json = objectMapper.writeValueAsString(result.body());
    long bytes = stagingService.stagedBytes(kind, json);
    if (json.getBytes(StandardCharsets.UTF_8).length > ingestProperties.maxHandoffBytes()
        || bytes > ingestProperties.maxHandoffBytes()) {
      return problem(
          HttpStatus.CONTENT_TOO_LARGE.value(),
          PAYLOAD_TOO_LARGE,
          "The draft is too large to hand off; send fewer entries.");
    }
    HandoffStagingService.Staged staged;
    try {
      staged =
          stageWithinBudget(
              context,
              bytes,
              () ->
                  stagingService.stageDraft(
                      context.clientId(),
                      context.member(),
                      kind,
                      json,
                      storeProperties.maxDraftsPerClientMember()));
      if (staged == null) {
        return budgetExhausted(context, request, bytes);
      }
    } catch (ExchangeUnavailableException | DataAccessException e) {
      log.warn("A draft could not be staged: {}", e.getClass().getSimpleName());
      countStagingFailure();
      return unavailable(
          ExchangeRefusals.SERVICE_UNAVAILABLE,
          "The draft cannot be staged; try again later.",
          RETRY_AFTER_SECONDS);
    }
    meterRegistry
        .counter(MetricNames.INGEST_HANDOFF, MetricNames.TAG_KIND, kind.name())
        .increment();
    ObjectNode answer = objectMapper.createObjectNode();
    answer.put(
        "frontendUrl",
        ingestProperties.frontendBaseUrl() + path + "?handoff=" + staged.handoffId());
    answer.put("handoffId", staged.handoffId());
    answer.put("kind", kind.name());
    return answer(answer, "draft-result.schema.json");
  }

  /**
   * Checks a change set, relays it and checks the answer; a change set of more than {@value
   * #LARGE_CHANGE_SET_OPS} ops is relayed within the large-set bulkhead, whose {@code RELAY_BUSY}
   * refusal gives the write's quota count back, and one the backend's mass-change guard held back
   * is staged for the member's confirmation.
   *
   * @param resource the resource's path segment
   * @param definition the change set's schema definition
   * @param body the change set
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the change result or a problem
   */
  private @NotNull ResponseEntity<?> changes(
      @NotNull String resource,
      @NotNull String definition,
      @NotNull JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    if (body.get("ops") instanceof ArrayNode ops && ops.size() > BATCH_MAX_OPS) {
      return problem(
          HttpStatus.CONTENT_TOO_LARGE.value(),
          BATCH_TOO_LARGE,
          "A change set holds at most " + BATCH_MAX_OPS + " ops.");
    }
    String schema = "change-set.schema.json#/$defs/" + definition;
    List<ExchangeSchemas.Violation> violations = schemas.validate(schema, body);
    if (!violations.isEmpty()) {
      return schemaInvalid(violations);
    }
    List<String> unknown = schemas.unknownFields(schema, body);
    ResponseEntity<?> unreportable = unreportable(unknown);
    if (unreportable != null) {
      return unreportable;
    }
    String target = BACKEND + "/me/" + resource + "/changes";
    ExchangeRelay.Result result =
        body.get("ops") instanceof ArrayNode ops && ops.size() > LARGE_CHANGE_SET_OPS
            ? relay.forwardLarge(HttpMethod.POST, target, body, context, acceptLanguage)
            : relay.forward(HttpMethod.POST, target, body, context, acceptLanguage);
    if (ExchangeRefusals.RELAY_BUSY.equals(result.code())) {
      quotas.refundCounted(request);
    }
    if (!result.isOk() && MASS_CHANGE_CONFIRMATION_REQUIRED.equals(result.code())) {
      return staged(context, request, resource, body, result);
    }
    return relayed(result, "change-result.schema.json", unknown);
  }

  /**
   * Stages a change set the mass-change guard held back, stamped with the time it was staged, and
   * answers where the member confirms it.
   *
   * @param context the admitted request
   * @param request the write, whose quota count a budget refusal gives back
   * @param resource the resource's path segment
   * @param body the change set
   * @param result the backend's refusal
   * @return {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} with the {@code confirmationUrl}, or a
   *     problem when the change set cannot be staged
   */
  private @NotNull ResponseEntity<?> staged(
      @NotNull ExchangeRequestContext context,
      @NotNull HttpServletRequest request,
      @NotNull String resource,
      @NotNull JsonNode body,
      @NotNull ExchangeRelay.Result result) {
    ObjectNode document = objectMapper.createObjectNode();
    document.put("clientId", context.clientId());
    document.put("installationKey", context.keyThumbprint());
    document.put("resource", resource);
    document.put("stagedAt", Instant.now().toString());
    document.set("changeSet", body);
    String json = objectMapper.writeValueAsString(document);
    long bytes = stagingService.stagedBytes(HandoffKind.MASS_CHANGE, json);
    if (json.getBytes(StandardCharsets.UTF_8).length > storeProperties.maxMassChangeBytes()
        || bytes > storeProperties.maxMassChangeBytes()) {
      return problem(
          HttpStatus.CONTENT_TOO_LARGE.value(),
          BATCH_TOO_LARGE,
          "The change set is too large to hold for confirmation; send smaller batches.");
    }
    HandoffStagingService.Staged staged;
    try {
      staged =
          stageWithinBudget(
              context,
              bytes,
              () ->
                  stagingService.stageMassChange(
                      context.clientId(),
                      context.member(),
                      json,
                      storeProperties.maxMassChangeBytes()));
      if (staged == null) {
        return budgetExhausted(context, request, bytes);
      }
    } catch (ExchangeUnavailableException | DataAccessException e) {
      log.warn("A mass change could not be staged: {}", e.getClass().getSimpleName());
      countStagingFailure();
      return unavailable(
          ExchangeRefusals.SERVICE_UNAVAILABLE,
          "The confirmation cannot be prepared; try again later.",
          RETRY_AFTER_SECONDS);
    }
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_MASS_CHANGES_STAGED, MetricNames.TAG_CLIENT_ID, context.clientId())
        .increment();
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            HttpStatus.CONFLICT,
            HttpStatus.CONFLICT.getReasonPhrase(),
            MASS_CHANGE_CONFIRMATION_REQUIRED,
            result.detail());
    problem.setProperty(
        "confirmationUrl",
        ingestProperties.frontendBaseUrl() + CONFIRMATION_PATH + "?handoff=" + staged.handoffId());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /**
   * Stages a handoff within the byte budget: reserves its size atomically, stages it and settles
   * the reservation on the staged key, or frees the reservation when staging fails.
   *
   * @param context the admitted request
   * @param bytes the size the handoff will be staged with
   * @param stage stages the handoff
   * @return the staged handoff, or {@code null} when it does not fit the budget
   * @throws ExchangeUnavailableException if the budget cannot be reached
   */
  private @Nullable HandoffStagingService.Staged stageWithinBudget(
      @NotNull ExchangeRequestContext context,
      long bytes,
      @NotNull Supplier<HandoffStagingService.Staged> stage) {
    String pending = ExchangeBudget.PENDING_PREFIX + UUID.randomUUID();
    if (!budget.reserve(
        context.clientId(), context.member(), pending, bytes, ingestProperties.handoffTtl())) {
      return null;
    }
    HandoffStagingService.Staged staged;
    try {
      staged = stage.get();
    } catch (RuntimeException e) {
      budget.release(context.clientId(), context.member(), pending, bytes);
      throw e;
    }
    if (!budget.settle(
        context.clientId(),
        context.member(),
        pending,
        bytes,
        staged.key(),
        staged.bytes(),
        ingestProperties.handoffTtl())) {
      log.warn("A staged handoff stays counted under its reservation");
    }
    return staged;
  }

  /**
   * Answers a staging the byte budget refused: gives the write's quota count back and tells the
   * client to retry once enough of the budget has expired to hold the staged bytes.
   *
   * @param context the admitted request
   * @param request the write
   * @param bytes the size the handoff would have been staged with
   * @return {@code 503 EXCHANGE_BUDGET_EXHAUSTED} with {@code Retry-After}
   */
  private @NotNull ResponseEntity<?> budgetExhausted(
      @NotNull ExchangeRequestContext context, @NotNull HttpServletRequest request, long bytes) {
    quotas.refundCounted(request);
    return unavailable(
        ExchangeRefusals.EXCHANGE_BUDGET_EXHAUSTED,
        BUDGET_EXHAUSTED_DETAIL,
        budget.retryAfterSeconds(context.clientId(), context.member(), bytes));
  }

  /**
   * Counts a draft or change set that could not be staged on {@code
   * basetool_ingest_handoff_errors_total{reason="staging_unavailable"}}, the series {@code
   * IngestStagingUnavailable} reads.
   */
  private void countStagingFailure() {
    meterRegistry
        .counter(
            MetricNames.INGEST_HANDOFF_ERRORS,
            MetricNames.TAG_REASON,
            MetricNames.REASON_STAGING_UNAVAILABLE)
        .increment();
  }

  /**
   * Answers that a store the exchange needs is full or unreachable.
   *
   * @param code the code
   * @param detail the detail
   * @param retryAfterSeconds the {@code Retry-After}
   * @return {@code 503} with {@code Retry-After}
   */
  private @NotNull ResponseEntity<?> unavailable(
      @NotNull String code, @NotNull String detail, long retryAfterSeconds) {
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            HttpStatus.SERVICE_UNAVAILABLE,
            HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
            code,
            detail);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /**
   * Checks the paging parameters, relays a page request and checks the answer.
   *
   * @param path the route below {@code /exchange/v1}
   * @param responseSchema the page's schema
   * @param cursor the cursor, or {@code null}
   * @param limit the page size, or {@code null}
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  private @NotNull ResponseEntity<?> page(
      @NotNull String path,
      @NotNull String responseSchema,
      @Nullable String cursor,
      @Nullable String limit,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    if (limit != null && !validLimit(limit)) {
      return schemaInvalid(
          List.of(
              new ExchangeSchemas.Violation(
                  "/limit", "must be an integer from 1 to " + PAGE_MAX_LIMIT)));
    }
    if (cursor != null && !CURSOR.matcher(cursor).matches()) {
      return problem(
          HttpStatus.GONE.value(), CURSOR_EXPIRED, "The cursor is not one the server issued.");
    }
    StringBuilder target = new StringBuilder(BACKEND).append(path);
    char separator = '?';
    if (cursor != null) {
      target.append(separator).append("cursor=").append(cursor);
      separator = '&';
    }
    if (limit != null) {
      target.append(separator).append("limit=").append(Integer.parseInt(limit));
    }
    return relayed(
        relay.forward(HttpMethod.GET, target.toString(), null, context, acceptLanguage),
        responseSchema,
        List.of());
  }

  /**
   * Whether a page size lies within the contract.
   *
   * @param limit the raw value
   * @return {@code true} for an integer from 1 to {@value #PAGE_MAX_LIMIT}
   */
  private static boolean validLimit(@NotNull String limit) {
    if (!LIMIT.matcher(limit).matches()) {
      return false;
    }
    int value = Integer.parseInt(limit);
    return value >= 1 && value <= PAGE_MAX_LIMIT;
  }

  /**
   * Checks a body, relays it and checks the answer.
   *
   * @param body the request body
   * @param requestSchema the body's schema
   * @param path the route below {@code /exchange/v1}
   * @param responseSchema the answer's schema
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the answer or a problem
   */
  private @NotNull ResponseEntity<?> relayBody(
      @NotNull JsonNode body,
      @NotNull String requestSchema,
      @NotNull String path,
      @NotNull String responseSchema,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    List<ExchangeSchemas.Violation> violations = schemas.validate(requestSchema, body);
    if (!violations.isEmpty()) {
      return schemaInvalid(violations);
    }
    List<String> unknown = schemas.unknownFields(requestSchema, body);
    ResponseEntity<?> unreportable = unreportable(unknown);
    if (unreportable != null) {
      return unreportable;
    }
    return relayed(
        relay.forward(HttpMethod.POST, BACKEND + path, body, context, acceptLanguage),
        responseSchema,
        unknown);
  }

  /**
   * Turns a relay result into the answer, checking a usable one against its schema and adding the
   * unknown-field warnings where the schema carries warnings.
   *
   * @param result the relay result
   * @param responseSchema the answer's schema
   * @param unknown the request's undeclared fields
   * @return the answer or a problem
   */
  private @NotNull ResponseEntity<?> relayed(
      @NotNull ExchangeRelay.Result result,
      @NotNull String responseSchema,
      @NotNull List<String> unknown) {
    if (!result.isOk()) {
      return problem(result.status(), result.code(), result.detail());
    }
    JsonNode body = result.body();
    if (!unknown.isEmpty()
        && body instanceof ObjectNode object
        && acceptsWarnings(responseSchema)) {
      ArrayNode warnings =
          object.get("warnings") instanceof ArrayNode existing
              ? existing
              : object.putArray("warnings");
      for (String pointer : unknown) {
        if (warnings.size() >= ExchangeSchemas.MAX_REPORTED) {
          break;
        }
        ObjectNode warning = warnings.addObject();
        warning.put("pointer", pointer);
        warning.put("code", UNKNOWN_FIELD);
      }
    }
    return answer(body, responseSchema);
  }

  /**
   * Answers with a document once it matches its schema.
   *
   * @param body the document
   * @param schema its schema
   * @return {@code 200}, or {@code 502} when the document breaks the contract
   */
  private @NotNull ResponseEntity<?> answer(@NotNull JsonNode body, @NotNull String schema) {
    List<ExchangeSchemas.Violation> violations = schemas.validate(schema, body);
    if (!violations.isEmpty()) {
      log.warn("Exchange answer breaks {} with {} violation(s)", schema, violations.size());
      return failed();
    }
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }

  /**
   * Whether a response schema carries a {@code warnings} list.
   *
   * @param responseSchema the schema's file name
   * @return {@code true} for the resolve and change answers
   */
  private static boolean acceptsWarnings(@NotNull String responseSchema) {
    return "resolve-response.schema.json".equals(responseSchema)
        || "change-result.schema.json".equals(responseSchema);
  }

  /**
   * Refuses a body whose undeclared field could not be reported as a warning, because its JSON
   * Pointer exceeds {@link ExchangeSchemas#MAX_POINTER} characters; checked before the relay, so a
   * write the backend committed always gets an answer that matches its schema.
   *
   * @param unknown the request's undeclared fields
   * @return {@code 400 SCHEMA_INVALID} naming the fields' parents, or {@code null} when every field
   *     can be reported
   */
  private @Nullable ResponseEntity<?> unreportable(@NotNull List<String> unknown) {
    List<ExchangeSchemas.Violation> violations =
        unknown.stream()
            .filter(pointer -> pointer.length() > ExchangeSchemas.MAX_POINTER)
            .map(
                pointer ->
                    new ExchangeSchemas.Violation(
                        ExchangeSchemas.reportable(pointer), "holds a property name too long"))
            .distinct()
            .toList();
    return violations.isEmpty() ? null : schemaInvalid(violations);
  }

  /**
   * Refuses an envelope whose {@code formatVersion} names a major other than {@value
   * #FORMAT_MAJOR}; every {@code 1.x} passes (REQ-XCH-019).
   *
   * @param envelope an envelope that already matches its schema
   * @return the violation at {@code /formatVersion}, or {@code null} when the major is supported
   */
  static @Nullable ExchangeSchemas.Violation unsupportedFormatMajor(@NotNull JsonNode envelope) {
    JsonNode version = envelope.get(FORMAT_VERSION);
    if (version == null || !version.isString()) {
      return null;
    }
    String value = version.stringValue();
    int dot = value.indexOf('.');
    String major = dot < 0 ? value : value.substring(0, dot);
    return FORMAT_MAJOR.equals(major)
        ? null
        : new ExchangeSchemas.Violation("/" + FORMAT_VERSION, UNSUPPORTED_MAJOR);
  }

  /**
   * Answers a request body that breaks its schema; every pointer is shortened to one the problem
   * schema can carry.
   *
   * @param violations the violations
   * @return {@code 400 SCHEMA_INVALID} with {@code errors[]}
   */
  private @NotNull ResponseEntity<?> schemaInvalid(
      @NotNull List<ExchangeSchemas.Violation> violations) {
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            HttpStatus.BAD_REQUEST,
            "Bad request",
            "SCHEMA_INVALID",
            "The body does not match the v1 schema.");
    problem.setProperty(
        "errors",
        violations.stream()
            .map(v -> new Problems.FieldError(ExchangeSchemas.reportable(v.pointer()), v.message()))
            .toList());
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /**
   * Answers a relay failure.
   *
   * @return {@code 502 BACKEND_RELAY_FAILED}
   */
  private @NotNull ResponseEntity<?> failed() {
    ExchangeRelay.Result failed = ExchangeRelay.Result.failed();
    return problem(failed.status(), failed.code(), failed.detail());
  }

  /**
   * Builds a problem answer, with the {@code Retry-After} the gateway's gate sends for a relayed
   * gate code that carries one.
   *
   * @param status the status
   * @param code the code
   * @param detail the detail
   * @return the answer
   */
  private @NotNull ResponseEntity<?> problem(
      int status, @Nullable String code, @Nullable String detail) {
    HttpStatus httpStatus = HttpStatus.valueOf(status);
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            httpStatus,
            httpStatus.getReasonPhrase(),
            code == null ? ExchangeRelay.RELAY_FAILED : code,
            detail);
    ResponseEntity.BodyBuilder answer =
        ResponseEntity.status(httpStatus).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    String retryAfter = ExchangeRelay.retryAfterSeconds(code);
    if (retryAfter != null) {
      answer.header(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return answer.body(problem);
  }
}
