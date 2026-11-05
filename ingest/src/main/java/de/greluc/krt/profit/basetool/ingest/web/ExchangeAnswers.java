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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prepares and checks the answers of the exchange routes: adds the request's {@code UNKNOWN_FIELD}
 * warnings where the answer carries warnings, and lets an answer reach the client only when it
 * holds its contract — it matches its schema and carries no property the schema does not declare,
 * while a declared free-form map passes verbatim (REQ-XCH-050).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ExchangeAnswers {

  /** The warning code of an undeclared field. */
  static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";

  private final ExchangeSchemas schemas;
  private final ExchangeRelay relay;

  /**
   * Adds a warning for each of the request's undeclared fields to an answer whose schema carries
   * warnings, after the warnings the backend already sent and up to {@link
   * ExchangeSchemas#MAX_REPORTED}.
   *
   * @param body the answer, changed in place
   * @param responseSchema the answer's schema
   * @param unknown JSON Pointers to the request's undeclared fields
   */
  void addUnknownFieldWarnings(
      @NotNull JsonNode body, @NotNull String responseSchema, @NotNull List<String> unknown) {
    if (unknown.isEmpty()
        || !(body instanceof ObjectNode object)
        || !carriesWarnings(responseSchema)) {
      return;
    }
    ArrayNode warnings =
        object.get("warnings") instanceof ArrayNode existing
            ? existing
            : object.putArray("warnings");
    for (String pointer : unknown) {
      if (warnings.size() >= ExchangeSchemas.MAX_REPORTED) {
        return;
      }
      ObjectNode warning = warnings.addObject();
      warning.put("pointer", pointer);
      warning.put("code", UNKNOWN_FIELD);
    }
  }

  /**
   * Checks a document that carries a usable backend answer and settles the relay outcome as {@code
   * ok} or {@code answer_contract_broken}.
   *
   * @param body the document
   * @param schema its schema
   * @param request the admitted request
   * @param context what the gate established
   * @return {@code true} when the document holds its contract
   */
  boolean passesRelayed(
      @NotNull JsonNode body,
      @NotNull String schema,
      @NotNull HttpServletRequest request,
      @NotNull ExchangeRequestContext context) {
    if (!passes(body, schema, request)) {
      relay.countContractBroken(context.clientId());
      return false;
    }
    relay.countPassedOn(context.clientId());
    return true;
  }

  /**
   * Checks a document against its contract; a breach is logged with the schema, the route and the
   * breaches' JSON Pointers, never a value.
   *
   * @param body the document
   * @param schema its schema
   * @param request the admitted request
   * @return {@code true} when the document holds its contract
   */
  boolean passes(
      @NotNull JsonNode body, @NotNull String schema, @NotNull HttpServletRequest request) {
    List<String> breaches = schemas.answerBreaches(schema, body);
    if (breaches.isEmpty()) {
      return true;
    }
    log.warn(
        "Exchange answer breaks {} on {} at {}",
        schema,
        request.getRequestURI(),
        breaches.stream().map(ExchangeSchemas::reportable).toList());
    return false;
  }

  /**
   * Whether a response schema carries a {@code warnings} list.
   *
   * @param responseSchema the schema's file name
   * @return {@code true} for the resolve and change answers
   */
  private static boolean carriesWarnings(@NotNull String responseSchema) {
    return "resolve-response.schema.json".equals(responseSchema)
        || "change-result.schema.json".equals(responseSchema);
  }
}
