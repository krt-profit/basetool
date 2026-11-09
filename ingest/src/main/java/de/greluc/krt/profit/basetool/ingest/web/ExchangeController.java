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

import static de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes.CHANGES;
import static de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes.GATEWAY;
import static de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes.MEMBER;
import static de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes.MEMBER_BLUEPRINTS;
import static de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes.MEMBER_REFINERY_ORDERS;
import static de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes.MEMBER_SHIPS;
import static de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes.MEMBER_STOCK;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeGatewayProperties;
import de.greluc.krt.profit.basetool.ingest.config.ExchangeLimitProperties;
import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.contract.ExchangeSchemas;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.Problems;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The authenticated exchange routes built so far (REQ-XCH-001). Each request has passed both
 * exchange gates; a body is checked against its v1 schema before the relay, undeclared fields are
 * reported as {@code UNKNOWN_FIELD} warnings where the answer carries warnings, and the backend's
 * answer reaches the client only when it matches its schema and carries no property the schema does
 * not declare (REQ-XCH-050). The committed exchange document describes these routes.
 */
@Slf4j
@RestController
@RequestMapping(GATEWAY)
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

  /** The largest page, as the contract fixes it. */
  static final int PAGE_MAX_LIMIT = 1000;

  /** The code of a cursor the server can no longer serve. */
  static final String CURSOR_EXPIRED = "CURSOR_EXPIRED";

  /** The code of a change set above the contract's size. */
  static final String BATCH_TOO_LARGE = "BATCH_TOO_LARGE";

  /** The code of a draft too large to hand off. */
  static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

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

  /** The relay prefix of the backend's exchange surface. */
  static final String BACKEND = "/api/v1/exchange";

  /** The change-set definition in {@code change-set.schema.json} of each of the member's feeds. */
  private static final Map<String, String> MEMBER_CHANGE_SETS =
      Map.of(
          MEMBER_BLUEPRINTS, "blueprintChangeSet",
          MEMBER_STOCK, "stockChangeSet",
          MEMBER_SHIPS, "shipChangeSet",
          MEMBER_REFINERY_ORDERS, "refineryOrderChangeSet");

  /** The page schema of each of the member's feeds. */
  private static final Map<String, String> MEMBER_FEED_PAGES =
      Map.of(
          MEMBER_BLUEPRINTS, "page.schema.json#/$defs/blueprintPage",
          MEMBER_STOCK, "page.schema.json#/$defs/stockPage",
          MEMBER_SHIPS, "page.schema.json#/$defs/shipPage",
          MEMBER_REFINERY_ORDERS, "page.schema.json#/$defs/refineryOrderPage");

  /** The shape of an id the gateway relays in a path or query. */
  static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$");

  /** The resource of the org stock's writes, which lie outside {@code /me}. */
  private static final String ORG_STOCK = "org-stock";

  /** Seconds a client waits when the daily write quota cannot be counted. */
  private static final long QUOTA_UNAVAILABLE_RETRY_AFTER_SECONDS = 30;

  private final ExchangeRelay relay;
  private final ExchangeSchemas schemas;
  private final ExchangeGatewayProperties properties;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final HandoffStagingService stagingService;
  private final ExchangeBudget budget;
  private final ExchangeQuotas quotas;
  private final ExchangeLimitProperties limitProperties;
  private final ExchangeRefusals refusals;
  private final ExchangeStoreProperties storeProperties;
  private final IngestProperties ingestProperties;
  private final MeterRegistry meterRegistry;
  private final HeldMassChanges heldMassChanges;
  private final ExchangeAnswers answers;

  /** Relays a read and turns the backend's answer or a refusal into the client's. */
  private final ExchangeRelayResponses responses;

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
      return responses.failed();
    }
    ObjectNode document = objectMapper.createObjectNode();
    document.put("apiVersion", API_VERSION);
    ArrayNode capabilities = document.putArray("capabilities");
    new TreeSet<>(context.capabilities()).forEach(capabilities::add);
    ExchangeRelay.Result installation =
        relay.forward(HttpMethod.GET, BACKEND + "/me/installation", null, context, acceptLanguage);
    if (ExchangeRelay.isGateCode(installation.code())) {
      return responses.problem(installation.status(), installation.code(), installation.detail());
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
    String schema = "service-document.schema.json";
    boolean passes =
        installation.isOk()
            ? answers.passesRelayed(document, schema, request, context)
            : answers.passes(document, schema, request);
    return passes ? responses.ok(document) : responses.failed();
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
    return responses.body(
        HttpMethod.POST,
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
    return responses.body(
        HttpMethod.POST,
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
    return responses.body(
        HttpMethod.POST,
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
      return responses.failed();
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET, BACKEND + "/catalog/locations", null, context, acceptLanguage),
        "location-list.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Returns a snapshot page of one of the member's own feeds — the blueprints, the personal stock
   * lots, the ships or the open and running refinery orders (REQ-XCH-051) — or with {@code cursor}
   * the changes since it.
   *
   * @param cursor the cursor of the last page, or {@code null} for a new snapshot
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping({MEMBER_BLUEPRINTS, MEMBER_STOCK, MEMBER_SHIPS, MEMBER_REFINERY_ORDERS})
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> memberFeed(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    String feed = matchedFeed(request);
    return responses.page(
        feed,
        Objects.requireNonNull(MEMBER_FEED_PAGES.get(feed)),
        cursor,
        limit,
        request,
        acceptLanguage);
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
      return responses.failed();
    }
    return responses.relayed(
        relay.forward(HttpMethod.GET, BACKEND + "/me/org-demand", null, context, acceptLanguage),
        "org-demand.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Returns the org units the member directly belongs to.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the org units or a problem
   */
  @GetMapping("/me/org-units")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> orgUnits(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    return responses.relayed(
        relay.forward(HttpMethod.GET, BACKEND + "/me/org-units", null, context, acceptLanguage),
        "org-unit-list.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Lists the members a refinery pickup's store lines may book onto.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the receivers or a problem
   */
  @GetMapping("/me/store-choices/receivers")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> storeChoiceReceivers(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET, BACKEND + "/me/store-choices/receivers", null, context, acceptLanguage),
        "store-choice-receivers.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Lists the active job orders the member sees that need a material, for a store line's earmark.
   *
   * @param material the material's id
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the job orders or a problem
   */
  @GetMapping("/me/store-choices/job-orders")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> storeChoiceJobOrders(
      @Nullable @RequestParam(required = false) String material,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    if (material == null || !UUID_SHAPE.matcher(material).matches()) {
      return responses.schemaInvalid(
          List.of(new ExchangeSchemas.Violation("/material", "must be a material id")));
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET,
            BACKEND + "/me/store-choices/job-orders?material=" + material.toLowerCase(Locale.ROOT),
            null,
            context,
            acceptLanguage),
        "store-choice-job-orders.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Returns a snapshot page of the shared stock of the member's direct units.
   *
   * @param cursor the cursor of the last page, or {@code null} for a new snapshot
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping("/org-stock")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> orgStock(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return responses.page(
        "/org-stock",
        "page.schema.json#/$defs/orgStockPage",
        cursor,
        limit,
        request,
        acceptLanguage);
  }

  /**
   * Lists the members a book-in into one of the member's Logistician units may name as holder.
   *
   * @param unit the unit's id
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the roster or a problem
   */
  @GetMapping("/org-stock/members")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> orgStockMembers(
      @Nullable @RequestParam(required = false) String unit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    if (unit == null || !UUID_SHAPE.matcher(unit).matches()) {
      return responses.schemaInvalid(
          List.of(new ExchangeSchemas.Violation("/unit", "must be a unit id")));
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET,
            BACKEND + "/org-stock/members?unit=" + unit.toLowerCase(Locale.ROOT),
            null,
            context,
            acceptLanguage),
        "org-stock-members.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Lists the job orders and missions an entry of one of the member's Logistician units may be
   * earmarked to, for one material or item.
   *
   * @param unit the unit's id
   * @param material the material's or item's id, as the org stock's lots name it
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the targets or a problem
   */
  @GetMapping("/org-stock/targets")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> orgStockTargets(
      @Nullable @RequestParam(required = false) String unit,
      @Nullable @RequestParam(required = false) String material,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    List<ExchangeSchemas.Violation> violations = new ArrayList<>();
    if (unit == null || !UUID_SHAPE.matcher(unit).matches()) {
      violations.add(new ExchangeSchemas.Violation("/unit", "must be a unit id"));
    }
    if (material == null || !UUID_SHAPE.matcher(material).matches()) {
      violations.add(new ExchangeSchemas.Violation("/material", "must be a material or item id"));
    }
    if (!violations.isEmpty()) {
      return responses.schemaInvalid(violations);
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET,
            BACKEND
                + "/org-stock/targets?unit="
                + Objects.requireNonNull(unit).toLowerCase(Locale.ROOT)
                + "&material="
                + Objects.requireNonNull(material).toLowerCase(Locale.ROOT),
            null,
            context,
            acceptLanguage),
        "org-stock-targets.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Applies a change set to one of the member's own resources — the blueprints, the stock lots, the
   * ships or the refinery orders (REQ-XCH-052); each set counts once against the daily write quota,
   * whatever its op count.
   *
   * @param body the change set
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the change result or a problem
   */
  @PostMapping(
      value = {
        MEMBER_BLUEPRINTS + CHANGES,
        MEMBER_STOCK + CHANGES,
        MEMBER_SHIPS + CHANGES,
        MEMBER_REFINERY_ORDERS + CHANGES
      },
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> memberChanges(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    String feed = matchedFeed(request);
    return changes(
        feed.substring(MEMBER.length()),
        Objects.requireNonNull(MEMBER_CHANGE_SETS.get(feed)),
        false,
        body,
        request,
        acceptLanguage);
  }

  /**
   * Reads the member feed a request of a feed or of its change set matched.
   *
   * @param request a request a member-feed route matched
   * @return the feed below {@code /exchange/v1}, such as {@code /me/stock}
   */
  private static @NotNull String matchedFeed(@NotNull HttpServletRequest request) {
    String path =
        String.valueOf(
                Objects.requireNonNull(
                    request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)))
            .substring(GATEWAY.length());
    return path.endsWith(CHANGES) ? path.substring(0, path.length() - CHANGES.length()) : path;
  }

  /**
   * Books stock into and out of the pools of the member's Logistician units; every op counts
   * against the daily write quota.
   *
   * @param body the change set
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the change result or a problem
   */
  @PostMapping(value = "/org-stock/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> orgStockChanges(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return changes(ORG_STOCK, "orgStockChangeSet", true, body, request, acceptLanguage);
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
   * and answers where the member opens it, counting the relay outcome on that answer.
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
      return responses.failed();
    }
    List<ExchangeSchemas.Violation> violations = schemas.validate(schema, body);
    if (!violations.isEmpty()) {
      return responses.schemaInvalid(violations);
    }
    ExchangeSchemas.Violation unsupported =
        kind == HandoffKind.BLUEPRINT ? unsupportedFormatMajor(body) : null;
    if (unsupported != null) {
      return responses.schemaInvalid(List.of(unsupported));
    }
    ExchangeRelay.Result result =
        relay.forward(
            HttpMethod.POST, BACKEND + "/me/drafts/" + resource, body, context, acceptLanguage);
    if (!result.isOk()) {
      return responses.problem(result.status(), result.code(), result.detail());
    }
    String json = objectMapper.writeValueAsString(result.body());
    long bytes = stagingService.stagedBytes(kind, json);
    if (json.getBytes(StandardCharsets.UTF_8).length > ingestProperties.maxHandoffBytes()
        || bytes > ingestProperties.maxHandoffBytes()) {
      return responses.problem(
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
    boolean passes = answers.passesRelayed(answer, "draft-result.schema.json", request, context);
    return passes ? responses.ok(answer) : responses.failed();
  }

  /**
   * Checks a change set, relays it and checks the answer; a change set of more than {@value
   * #LARGE_CHANGE_SET_OPS} ops is relayed within the large-set bulkhead, whose {@code RELAY_BUSY}
   * refusal gives the write's quota count back, and one the backend's mass-change guard held back
   * is staged for the member's confirmation. Where every op counts, the ops after the first of a
   * change set that is not a dry run are counted against the daily write quota once it matches its
   * schema, and a change set over the quota is refused with them given back.
   *
   * @param resource the resource: {@code org-stock}, or the segment below {@code /me} of a member
   *     resource
   * @param definition the change set's schema definition
   * @param everyOpCounts whether every op counts against the daily write quota
   * @param body the change set
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the change result or a problem
   */
  private @NotNull ResponseEntity<?> changes(
      @NotNull String resource,
      @NotNull String definition,
      boolean everyOpCounts,
      @NotNull JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    if (body.get("ops") instanceof ArrayNode ops && ops.size() > BATCH_MAX_OPS) {
      return responses.problem(
          HttpStatus.CONTENT_TOO_LARGE.value(),
          BATCH_TOO_LARGE,
          "A change set holds at most " + BATCH_MAX_OPS + " ops.");
    }
    String schema = "change-set.schema.json#/$defs/" + definition;
    List<ExchangeSchemas.Violation> violations = schemas.validate(schema, body);
    if (!violations.isEmpty()) {
      return responses.schemaInvalid(violations);
    }
    List<String> unknown = schemas.unknownFields(schema, body);
    ResponseEntity<?> unreportable = responses.unreportable(unknown);
    if (unreportable != null) {
      return unreportable;
    }
    if (everyOpCounts
        && !body.path("dryRun").asBoolean(false)
        && body.get("ops") instanceof ArrayNode ops
        && ops.size() > 1) {
      ResponseEntity<?> overQuota = countFurtherOps(context, request, ops.size() - 1L);
      if (overQuota != null) {
        return overQuota;
      }
    }
    String digest = heldMassChanges.digest(context, resource, body);
    ResponseEntity<?> resent = heldMassChanges.resent(context, digest);
    if (resent != null) {
      return resent;
    }
    String target = BACKEND + (ORG_STOCK.equals(resource) ? "/" : "/me/") + resource + "/changes";
    ExchangeRelay.Result result =
        body.get("ops") instanceof ArrayNode ops && ops.size() > LARGE_CHANGE_SET_OPS
            ? relay.forwardLarge(HttpMethod.POST, target, body, context, acceptLanguage)
            : relay.forward(HttpMethod.POST, target, body, context, acceptLanguage);
    if (ExchangeRefusals.RELAY_BUSY.equals(result.code())) {
      quotas.refundCounted(request);
    }
    if (!result.isOk() && HeldMassChanges.MASS_CHANGE_CONFIRMATION_REQUIRED.equals(result.code())) {
      return staged(context, request, resource, body, result, digest);
    }
    return responses.relayed(result, "change-result.schema.json", unknown, context, request);
  }

  /**
   * Counts the further ops of a change set whose every op counts against the member's daily write
   * quota (REQ-XCH-045), and refuses it with those counts given back when the quota is exceeded.
   *
   * @param context the admitted request
   * @param request the write, counted once by the limit filter
   * @param ops how many further ops to count
   * @return {@code 429 QUOTA_EXCEEDED} or {@code 503 SERVICE_UNAVAILABLE}, or {@code null} when the
   *     change set is within the quota
   */
  private @Nullable ResponseEntity<?> countFurtherOps(
      @NotNull ExchangeRequestContext context, @NotNull HttpServletRequest request, long ops) {
    long count;
    try {
      count = quotas.countFurtherOps(request, ops);
    } catch (ExchangeUnavailableException _) {
      refusals.count(ExchangeRefusals.SERVICE_UNAVAILABLE, context.clientId());
      return unavailable(
          ExchangeRefusals.SERVICE_UNAVAILABLE,
          "The write quota cannot be counted; try again later.",
          QUOTA_UNAVAILABLE_RETRY_AFTER_SECONDS);
    }
    Integer override = context.client().writesPerDay();
    int limit = override == null ? limitProperties.writesPerDay() : override;
    if (count <= limit) {
      return null;
    }
    quotas.refundFurtherOps(request);
    refusals.count(ExchangeRefusals.QUOTA_EXCEEDED, context.clientId());
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            HttpStatus.TOO_MANY_REQUESTS,
            "Refused",
            ExchangeRefusals.QUOTA_EXCEEDED,
            "The daily write quota is exhausted.");
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(quotas.secondsUntilTomorrow()))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /**
   * Stages a change set the mass-change guard held back, stamped with the time it was staged,
   * records its digest so a byte-identical resend finds it, and answers where the member confirms
   * it.
   *
   * @param context the admitted request
   * @param request the write, whose quota count a budget refusal gives back
   * @param resource the resource's path segment
   * @param body the change set
   * @param result the backend's refusal
   * @param digest the change set's digest, or {@code null} when none was computed
   * @return {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} with the {@code confirmationUrl}, or a
   *     problem when the change set cannot be staged
   */
  private @NotNull ResponseEntity<?> staged(
      @NotNull ExchangeRequestContext context,
      @NotNull HttpServletRequest request,
      @NotNull String resource,
      @NotNull JsonNode body,
      @NotNull ExchangeRelay.Result result,
      @Nullable String digest) {
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
      return responses.problem(
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
    return heldMassChanges.staged(context, digest, staged.handoffId(), result.detail());
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
}
