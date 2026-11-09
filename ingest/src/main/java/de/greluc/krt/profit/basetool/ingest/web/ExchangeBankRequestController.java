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

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeSchemas;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The member's own bank booking requests on the gateway: a snapshot and its change feed
 * (REQ-BXCH-003), and the writes that raise, correct and cancel them (REQ-BXCH-004). The paging or
 * body is checked before the relay and the answer against its schema after it; the bank switch, the
 * route scope, the idempotency key and the write quota are checked before a handler runs.
 */
@RestController
@RequestMapping(ExchangeBankRequestController.REQUESTS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangeBankRequestController {

  /** The request routes on the gateway. */
  static final String REQUESTS = "/exchange/v1/bank/requests";

  /** The request routes below the relay prefix. */
  private static final String BANK_REQUESTS = "/bank/requests";

  /** The schema of the request writes' bodies and answers. */
  private static final String WRITE_SCHEMA = "bank-request-write.schema.json#/$defs/";

  private final ExchangeRelayResponses responses;

  /**
   * Returns a snapshot page or a feed page of the member's own booking requests (REQ-BXCH-003).
   *
   * @param cursor the cursor of the last page, or {@code null} for a new snapshot
   * @param limit the page size, 1 to 1000, or {@code null} for the default
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> bankRequests(
      @Nullable @RequestParam(required = false) String cursor,
      @Nullable @RequestParam(required = false) String limit,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return responses.page(
        BANK_REQUESTS,
        "page.schema.json#/$defs/bankRequestPage",
        cursor,
        limit,
        request,
        acceptLanguage);
  }

  /**
   * Raises a booking request for the member (REQ-BXCH-004).
   *
   * @param body the request
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return {@code 201} with the request's id, status, version and amount, or a problem
   */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> raise(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return responses.body(
        HttpMethod.POST,
        body,
        WRITE_SCHEMA + "raise",
        BANK_REQUESTS,
        WRITE_SCHEMA + "written",
        HttpStatus.CREATED,
        request,
        acceptLanguage);
  }

  /**
   * Corrects one of the member's pending requests (REQ-BXCH-004).
   *
   * @param id the request's id
   * @param body the corrected values and the version
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the request's id, status, version and amount, or a problem
   */
  @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> correct(
      @NotNull @PathVariable String id,
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    if (!ExchangeController.UUID_SHAPE.matcher(id).matches()) {
      return malformedId();
    }
    return responses.body(
        HttpMethod.PUT,
        body,
        WRITE_SCHEMA + "correction",
        BANK_REQUESTS + "/" + id.toLowerCase(Locale.ROOT),
        WRITE_SCHEMA + "written",
        request,
        acceptLanguage);
  }

  /**
   * Cancels one of the member's pending requests (REQ-BXCH-004).
   *
   * @param id the request's id
   * @param body the version
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the request's id, status and version, or a problem
   */
  @PostMapping(value = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> cancel(
      @NotNull @PathVariable String id,
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    if (!ExchangeController.UUID_SHAPE.matcher(id).matches()) {
      return malformedId();
    }
    return responses.body(
        HttpMethod.POST,
        body,
        WRITE_SCHEMA + "cancel",
        BANK_REQUESTS + "/" + id.toLowerCase(Locale.ROOT) + "/cancel",
        WRITE_SCHEMA + "cancelled",
        request,
        acceptLanguage);
  }

  /**
   * Refuses a request id that is not a UUID before the relay.
   *
   * @return {@code 400 SCHEMA_INVALID} at {@code /id}
   */
  private @NotNull ResponseEntity<?> malformedId() {
    return responses.schemaInvalid(
        List.of(new ExchangeSchemas.Violation("/id", "must be a request id")));
  }
}