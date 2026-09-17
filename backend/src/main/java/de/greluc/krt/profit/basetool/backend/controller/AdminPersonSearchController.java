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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.service.PersonSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin Personensuche: every mention of a name across the free-text surfaces (REQ-SEC-060).
 *
 * <p>ADMIN only, and the reason is not only cost. A query that returns every place a given name
 * appears is a profile of that person assembled across the whole system — which is exactly what an
 * Art. 16 or Art. 17 request needs, and exactly why nobody else may run it.
 */
@RestController
@RequestMapping("/api/v1/admin/person-search")
@RequiredArgsConstructor
public class AdminPersonSearchController {

  private final PersonSearchService personSearchService;

  /**
   * Searches every registered free-text column for the term, case-insensitively.
   *
   * <p>The search itself is audit-logged. It is a read rather than a mutation, so it would normally
   * leave no trace — but it is a read of everything the system knows about a named person, and the
   * one operation here whose misuse would otherwise be invisible. The payload records the <b>length
   * of the term and the hit count, never the term itself</b>: the term is somebody's name, and
   * REQ-AUDIT-001 keeps user free text out of the details payload.
   *
   * @param q the name to look for; at least {@link PersonSearchService#MIN_TERM_LENGTH} characters
   * @return the hits, and whether the result was capped
   */
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Find every mention of a name across the free-text surfaces",
      description =
          "Case-insensitive substring search over every registered free-text column - external "
              + "mission participants, party leads, handover recipients, org-chart placeholders, "
              + "notes, booking reasons and the audit trails. Bounded: 25 hits per column, 300 in "
              + "total, and the response says when it was capped. Run it for EVERY Art. 16 or "
              + "Art. 17 request: a rectification that fixes one of four occurrences is not one.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The hits, possibly capped"),
    @ApiResponse(responseCode = "400", description = "The term was too short")
  })
  public PersonSearchService.PersonSearchResult search(@RequestParam("q") String q) {
    PersonSearchService.PersonSearchResult result = personSearchService.search(q);
    personSearchService.recordSearch(q, result);
    return result;
  }
}
