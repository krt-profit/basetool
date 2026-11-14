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
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The patch change on the gateway (REQ-PATCH-050…052): the read of the latest patch change with the
 * member's home-location prompt, and the member's choice, relayed to the backend with the body and
 * the answer checked against their schemas. The route scopes, the idempotency key and the write
 * quota are checked by the gate before a handler runs; the choice counts as one write.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExchangePatchChangeController {

  /** The read's route below the gateway. */
  private static final String PATCH_CHANGE = ExchangeRoutes.MEMBER_PATCH_CHANGE;

  /** The choice's route below the gateway. */
  private static final String HOME_LOCATION = PATCH_CHANGE + ExchangeRoutes.HOME_LOCATION;

  /** The schema of the read's answer and of the choice's body and answer. */
  private static final String SCHEMA = "patch-change.schema.json";

  private final ExchangeRelay relay;
  private final ExchangeRelayResponses responses;

  /**
   * Reads the latest patch change and the member's home-location prompt.
   *
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the patch change or a problem
   */
  @GetMapping(ExchangeRoutes.GATEWAY + PATCH_CHANGE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> patchChange(
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
            ExchangeController.BACKEND + PATCH_CHANGE,
            null,
            context,
            acceptLanguage),
        SCHEMA,
        List.of(),
        context,
        request);
  }

  /**
   * Makes the member's home-location choice, which relocates the member.
   *
   * @param body the chosen home location
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the chosen home location and what moved, or a problem
   */
  @PostMapping(
      value = ExchangeRoutes.GATEWAY + HOME_LOCATION,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public @NotNull ResponseEntity<?> chooseHomeLocation(
      @NotNull @RequestBody JsonNode body,
      @NotNull HttpServletRequest request,
      @Nullable @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage) {
    return responses.body(
        HttpMethod.POST,
        body,
        SCHEMA + "#/$defs/homeLocationChoice",
        HOME_LOCATION,
        SCHEMA + "#/$defs/homeLocationChosen",
        request,
        acceptLanguage);
  }
}
