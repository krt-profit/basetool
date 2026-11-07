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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialP4kOverride;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the admin material page's P4K-override release to {@code
 * /api/v1/materials/{id}/p4k-overrides/{field}} (REQ-P4K-024, REQ-P4K-025); a backend refusal is
 * relayed as {@code application/problem+json} with the backend's status and problem code.
 */
@RestController
@RequestMapping("/admin/materials")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminMaterialsRelayController {

  /** Writes the materials. */
  private final CatalogueBackendClient catalogueClient;

  /** Evicts the material catalogue after a write. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Hands an overridden resource group or refined material back to the P4K import, which writes the
   * P4K value on its next APPLY, and evicts {@link CacheDomain#MATERIAL} on success.
   *
   * @param id material id
   * @param field the overridden field
   * @param version the material version the admin saw
   * @return the material, the relayed backend status on failure (a stale version is {@code 409}),
   *     or {@code 500} on an unexpected error
   */
  @DeleteMapping(
      value = "/{id}/p4k-overrides/{field}/ajax",
      headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> releaseP4kOverride(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull MaterialP4kOverride field,
      @RequestParam long version) {
    return relay(
        log,
        "release material P4K override",
        () -> {
          MaterialDto released = catalogueClient.releaseMaterialP4kOverride(id, field, version);
          cacheEviction.evict(CacheDomain.MATERIAL);
          return ResponseEntity.ok(released);
        });
  }
}
