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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two store-choice reads a refinery pickup's store lines need (REQ-XCH-054), relayed to the
 * backend and checked against their schemas. The route scope {@code exchange.refinery.store} is
 * checked by the gate before the handler runs.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangeStoreChoiceController {

  private final ExchangeRelay relay;
  private final ExchangeRelayResponses responses;

  /**
   * Lists the members a refinery pickup's store lines may book onto.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the receivers or a problem
   */
  @GetMapping("/exchange/v1/me/store-choices/receivers")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> storeChoiceReceivers(
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return relayGet(
        "/me/store-choices/receivers",
        "store-choice-receivers.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Lists the active job orders the member sees that need a material, for a store line's earmark.
   *
   * @param material the material's id
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the job orders or a problem
   */
  @GetMapping("/exchange/v1/me/store-choices/job-orders")
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> storeChoiceJobOrders(
      @Nullable @RequestParam(required = false) String material,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    if (material == null || !ExchangeController.UUID_SHAPE.matcher(material).matches()) {
      return responses.schemaInvalid(
          List.of(new ExchangeSchemas.Violation("/material", "must be a material id")));
    }
    return relayGet(
        "/me/store-choices/job-orders?material=" + material.toLowerCase(Locale.ROOT),
        "store-choice-job-orders.schema.json",
        request,
        acceptLanguage);
  }

  /**
   * Relays a read below the backend's exchange surface and checks its answer.
   *
   * @param target the path and query below {@code /api/v1/exchange}
   * @param responseSchema the answer's schema
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the answer or a problem
   */
  private @NotNull ResponseEntity<?> relayGet(
      @NotNull String target,
      @NotNull String responseSchema,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return responses.failed();
    }
    return responses.relayed(
        relay.forward(
            HttpMethod.GET, ExchangeController.BACKEND + target, null, context, acceptLanguage),
        responseSchema,
        List.of(),
        context,
        request);
  }
}
