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
 * Authenticated REST proxy from {@code /api/proxy/materials/**} to the backend's {@code
 * /api/v1/materials/**} read endpoints.
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
   * Returns the terminals trading the given material; empty on backend failure.
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
   * Forwards the profit-calculation query to the backend, passing each star-system name as a
   * separately encoded {@code starSystemNames} parameter (REQ-SEC-051). Without star systems the
   * calculation spans all systems.
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
