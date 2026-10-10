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

package de.greluc.krt.profit.basetool.frontend.orgchart.client;

import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartPositionUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the org chart and its admin editor (REQ-ROLE-006), over {@link
 * BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class OrgChartBackendClient {

  /** The backend's position endpoints. */
  private static final String POSITIONS = "/api/v1/org-chart/positions";

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads the whole org chart.
   *
   * @return the chart, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgChartDto orgChart() {
    return backendApiClient.get("/api/v1/org-chart", OrgChartDto.class);
  }

  /**
   * Creates a seat.
   *
   * @param request the new seat
   * @return the created seat
   */
  @Nullable
  public OrgChartPositionDto createPosition(@Nullable OrgChartPositionCreateRequest request) {
    return backendApiClient.post(POSITIONS, request, OrgChartPositionDto.class);
  }

  /**
   * Reassigns or reorders a seat, carrying the optimistic-lock version in the request.
   *
   * @param id the seat
   * @param request the edit
   * @return the updated seat
   */
  @Nullable
  public OrgChartPositionDto updatePosition(
      @NotNull UUID id, @Nullable OrgChartPositionUpdateRequest request) {
    return backendApiClient.put(POSITIONS + "/{id}", request, OrgChartPositionDto.class, id);
  }

  /**
   * Clears a Kommando's leader while keeping the Kommando, its deputy and its ensigns.
   *
   * @param id the Kommando seat
   * @param version the optimistic-lock version the editor last saw
   */
  public void vacateLeader(@NotNull UUID id, long version) {
    backendApiClient.delete(POSITIONS + "/{id}/leader?version={version}", Void.class, id, version);
  }

  /**
   * Removes a seat, and with a Kommando leader its deputy and ensigns.
   *
   * @param id the seat
   */
  public void deletePosition(@NotNull UUID id) {
    backendApiClient.delete(POSITIONS + "/{id}", Void.class, id);
  }
}
