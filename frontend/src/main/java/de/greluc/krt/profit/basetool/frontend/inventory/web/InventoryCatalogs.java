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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MissionReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads the reference catalogs the Lager pages and forms offer; a failed read yields an empty list,
 * never an error.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class InventoryCatalogs {

  /** The inventory domain's typed backend client. */
  private final InventoryBackendClient inventoryClient;

  /**
   * Reads the user catalog.
   *
   * @return the users, empty when the read fails
   */
  @NotNull
  List<UserReferenceDto> users() {
    return load("user-catalog", inventoryClient::users);
  }

  /**
   * Reads the material catalog.
   *
   * @return the materials, empty when the read fails
   */
  @NotNull
  List<MaterialReferenceDto> materials() {
    return load("material-catalog", inventoryClient::materials);
  }

  /**
   * Reads the storage-location catalog.
   *
   * @return the locations, empty when the read fails
   */
  @NotNull
  List<LocationReferenceDto> locations() {
    return load("location-catalog", inventoryClient::locations);
  }

  /**
   * Reads the active-order catalog, optionally with each order's outstanding per-material need
   * (REQ-INV-039).
   *
   * @param withNeeds whether to request the per-material need figures
   * @return the active orders the caller may see, empty when the lookup fails
   */
  @NotNull
  List<JobOrderReferenceDto> activeJobOrders(boolean withNeeds) {
    return load("job-order-catalog", () -> inventoryClient.activeJobOrders(withNeeds));
  }

  /**
   * Reads the mission catalog.
   *
   * @return the missions, empty when the read fails
   */
  @NotNull
  List<MissionReferenceDto> missions() {
    return load("mission-catalog", inventoryClient::missions);
  }

  @NotNull
  private <T> List<T> load(@NotNull String section, @NotNull Supplier<List<T>> read) {
    List<T> content = SectionFallback.load(log, section, read, null);
    return content != null ? new ArrayList<>(content) : new ArrayList<>();
  }
}
