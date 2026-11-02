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

package de.greluc.krt.profit.basetool.frontend.inventory.client;

import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelCreateRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.OrgUnitReferenceDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the minimum stock levels ({@code /api/v1/inventory/stock-levels}) over
 * {@link BackendApiClient} (REQ-INV-068).
 */
@Service
@RequiredArgsConstructor
public class StockLevelBackendClient {

  private static final String PATH = "/api/v1/inventory/stock-levels";

  private static final ParameterizedTypeReference<List<StockLevelDto>> LEVEL_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitReferenceDto>> UNIT_LIST =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Reads the levels in the caller's Lager scope with their figures.
   *
   * @return the levels, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<StockLevelDto> levels() {
    return backendApiClient.get(PATH, LEVEL_LIST);
  }

  /**
   * Reads the low levels in the caller's Lager scope, emptiest first.
   *
   * @param orgUnitId only this unit, or {@code null} for every unit in scope
   * @return the low levels, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<StockLevelDto> lowLevels(@Nullable UUID orgUnitId) {
    return orgUnitId == null
        ? backendApiClient.get(PATH + "?lowOnly=true", LEVEL_LIST)
        : backendApiClient.get(PATH + "?lowOnly=true&orgUnitId={unit}", LEVEL_LIST, orgUnitId);
  }

  /**
   * Reads the units whose levels the caller may maintain.
   *
   * @return the units, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitReferenceDto> maintainableUnits() {
    return backendApiClient.get(PATH + "/units", UNIT_LIST);
  }

  /**
   * Sets a new level.
   *
   * @param request the unit, material, tier and amounts
   * @return the new level, or {@code null} when the backend sent no body
   */
  @Nullable
  public StockLevelDto create(@NotNull StockLevelCreateRequest request) {
    return backendApiClient.post(PATH, request, StockLevelDto.class);
  }

  /**
   * Changes a level.
   *
   * @param id the level
   * @param request the new values and the echoed version
   * @return the level, or {@code null} when the backend sent no body
   */
  @Nullable
  public StockLevelDto update(@NotNull UUID id, @NotNull StockLevelUpdateRequest request) {
    return backendApiClient.put(PATH + "/{id}", request, StockLevelDto.class, id);
  }

  /**
   * Removes a level.
   *
   * @param id the level
   * @param version the echoed version
   */
  public void delete(@NotNull UUID id, long version) {
    backendApiClient.delete(PATH + "/{id}?version={version}", Void.class, id, version);
  }
}
