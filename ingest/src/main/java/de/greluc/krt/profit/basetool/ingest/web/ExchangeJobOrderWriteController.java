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

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes;
import de.greluc.krt.profit.basetool.ingest.contract.ExchangeSchemas;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The member's job-order writes on the gateway under {@code exchange.joborders.write}
 * (REQ-OXCH-004, REQ-OXCH-005, REQ-OXCH-006, REQ-OXCH-007): the create options, the create of an
 * aUEC order, its cancel and its target change. The order id and the body are checked before the
 * relay and the answer against its schema after it; the job-order switches, the route scope, the
 * idempotency key and the write quota are checked before a handler runs.
 */
@RestController
@RequestMapping(ExchangeRoutes.JOB_ORDERS_PATH)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangeJobOrderWriteController {

  /** The job-order routes below the relay prefix. */
  private static final String JOB_ORDERS = "/job-orders";

  /** The schema of the job-order writes' bodies and answers. */
  private static final String WRITE_SCHEMA = "job-order-write.schema.json#/$defs/";

  private final ExchangeRelayResponses responses;
  private final ExchangeRelay relay;

  /**
   * Returns the pairs the member may create an aUEC order for.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the create options or a problem
   */
  @GetMapping("/create-options")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> createOptions(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET,
            ExchangeController.BACKEND + JOB_ORDERS + "/create-options",
            null,
            context,
            acceptLanguage),
        "job-order-create-options.schema.json",
        List.of(),
        context,
        request);
  }

  /**
   * Creates an aUEC order for the member, relaying the client's {@code Idempotency-Key}.
   *
   * @param body the pair, contact, comment and target
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return {@code 201} with the order's id, status, versions and target, or a problem
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> create(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return responses.keyedBody(
        HttpMethod.POST,
        body,
        WRITE_SCHEMA + "create",
        JOB_ORDERS,
        WRITE_SCHEMA + "created",
        HttpStatus.CREATED,
        request,
        acceptLanguage);
  }

  /**
   * Cancels an aUEC order of the member's unit before its first accepted money operation.
   *
   * @param id the order's id
   * @param body the lifecycle version
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the order's id, status and versions, or a problem
   */
  @PostMapping(value = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> cancel(
      @NotNull @PathVariable String id,
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return orderWrite(id, "cancel", body, "cancel", "written", request, acceptLanguage);
  }

  /**
   * Changes the target of an aUEC order of the member's unit to an amount above its net handover.
   *
   * @param id the order's id
   * @param body the new target and the target version
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the order's id, status, versions and target, or a problem
   */
  @PostMapping(value = "/{id}/target", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> changeTarget(
      @NotNull @PathVariable String id,
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return orderWrite(id, "target", body, "targetChange", "targetChanged", request, acceptLanguage);
  }

  /**
   * Relays a write on an existing order: the id must be a UUID, the body is checked against its
   * schema before the relay and the answer after it; the key stays on the gateway.
   *
   * @param id the order's id as the path carries it
   * @param action the route's last path segment
   * @param body the request body
   * @param requestDef the body's definition in the write schema
   * @param answerDef the answer's definition in the write schema
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the answer, or {@code 400 SCHEMA_INVALID} at {@code /id}, or a problem
   */
  private @NotNull ResponseEntity<?> orderWrite(
      @NotNull String id,
      @NotNull String action,
      @NotNull JsonNode body,
      @NotNull String requestDef,
      @NotNull String answerDef,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    if (!ExchangeController.UUID_SHAPE.matcher(id).matches()) {
      return responses.schemaInvalid(
          List.of(new ExchangeSchemas.Violation("/id", "must be a job order id")));
    }
    return responses.body(
        HttpMethod.POST,
        body,
        WRITE_SCHEMA + requestDef,
        JOB_ORDERS + "/" + id.toLowerCase(Locale.ROOT) + "/" + action,
        WRITE_SCHEMA + answerDef,
        request,
        acceptLanguage);
  }
}
