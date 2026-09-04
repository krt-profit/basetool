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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST proxy that forwards material-related read requests from the browser to the backend.
 *
 * <p>Browser-side JS calls land here under {@code /api/proxy/materials/**}; the controller adds the
 * bearer token via {@link BackendApiClient} and forwards to the corresponding {@code
 * /api/v1/materials/**} backend endpoint. Authentication is enforced at this seam
 * ({@code @PreAuthorize("isAuthenticated()")}) so an unauthenticated browser can never hit the
 * proxy and the backend never sees an unauthenticated request via this path.
 */
@RestController
@RequestMapping("/api/proxy/materials")
@RequiredArgsConstructor
public class MaterialProxyController {

  /**
   * Response type for the raw-JSON list payloads this proxy forwards ({@code terminals} and {@code
   * profit-calculation}). A shared static {@link ParameterizedTypeReference} is behaviourally
   * identical to a fresh anonymous instance per call (Q10).
   */
  private static final ParameterizedTypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Returns the list of terminals where the given material is traded. Empty list on backend failure
   * or missing payload — the frontend renders an "unavailable" placeholder rather than propagating
   * the error.
   *
   * @param id material id
   * @return list of terminal records (raw JSON maps), never {@code null}
   */
  @GetMapping("/{id}/terminals")
  @PreAuthorize("isAuthenticated()")
  public List<Map<String, Object>> getMaterialTerminals(@PathVariable UUID id) {
    List<Map<String, Object>> response =
        backendApiClient.get("/api/v1/materials/" + id + "/terminals", MAP_LIST_TYPE);
    return response != null ? response : List.of();
  }

  /**
   * Forwards the profit-calculation query to the backend, appending each star-system name as a
   * repeated {@code starSystemNames} query parameter (Spring's default list-binding form). The
   * star-system filter is optional — omitting it returns the calculation across all systems.
   *
   * <p>Each selected system is relayed as its own {@code {fN}} URI-template variable, so the {@code
   * WebClient} encodes it per RFC 3986 (REQ-SEC-051). A star-system name is free text out of the
   * UEX catalogue rather than a closed vocabulary the backend declares a type for, so it is escaped
   * exactly once across the hop instead of narrowed — the same treatment {@code
   * MaterialsPageController#filteredMatrixTemplate} gives the identical values on the materials
   * matrix. {@code shipId} binds as a {@link UUID}, which cannot express URI syntax, so it is safe
   * to concatenate.
   *
   * <p><b>Corrected 2026-09-04</b> (CodeQL {@code java/ssrf}, alert 877). This method previously
   * built the URI with {@link org.springframework.web.util.UriComponentsBuilder} and called {@code
   * builder.build().toUriString()} under a comment asserting that each query value "gets
   * URL-encoded". It did not: {@code UriComponentsBuilder#toUriString()} is {@code
   * build().encode().toUriString()} and does encode, but {@code build()} alone returns {@code
   * UriComponents} in the RAW encode state and {@code UriComponents#toUriString()} emits it
   * verbatim. A star-system name carrying {@code &} or {@code =} therefore injected additional
   * query parameters into the backend call — the same one-call-apart defect REQ-SEC-051's warning
   * callout was written for, in its third spelling.
   *
   * @param shipId chosen ship's id (defines capacity)
   * @param starSystemNames optional list of star-system names to constrain the source terminals
   * @return list of profit-calculation rows, never {@code null}
   */
  @GetMapping("/profit-calculation")
  @PreAuthorize("isAuthenticated()")
  public List<Map<String, Object>> getProfitCalculation(
      @RequestParam UUID shipId, @RequestParam(required = false) List<String> starSystemNames) {

    StringBuilder uriTemplate =
        new StringBuilder("/api/v1/materials/profit-calculation?shipId=").append(shipId);
    List<Object> uriVariables = new ArrayList<>();
    if (starSystemNames != null) {
      for (String starSystemName : starSystemNames) {
        uriTemplate.append("&starSystemNames={f").append(uriVariables.size()).append('}');
        uriVariables.add(starSystemName);
      }
    }

    List<Map<String, Object>> response =
        backendApiClient.get(uriTemplate.toString(), MAP_LIST_TYPE, uriVariables.toArray());
    return response != null ? response : List.of();
  }
}
