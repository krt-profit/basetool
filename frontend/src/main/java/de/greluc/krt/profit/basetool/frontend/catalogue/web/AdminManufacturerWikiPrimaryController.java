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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.SyncReportBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerWikiPrimaryRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX endpoint of the SC Wiki sync-report tab that chooses the primary Wiki entry of a
 * manufacturer the Wiki lists more than once (ADR-0356).
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminManufacturerWikiPrimaryController {

  /** Relays the choice to the backend. */
  private final SyncReportBackendClient syncReportClient;

  /**
   * Makes one SC Wiki entry of a manufacturer its primary link; the others stay aliases.
   *
   * @param manufacturerId the local manufacturer id
   * @param scwikiUuid the Wiki entry that becomes primary
   * @param version the manufacturer version the page rendered
   * @return {@code 204}; a backend failure (400, 404, 409) is answered by the global handler with
   *     the backend's status
   */
  @NotNull
  @PostMapping(
      value = "/admin/sync-reports/manufacturer-wiki-primary",
      headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Void> choosePrimary(
      @RequestParam UUID manufacturerId,
      @RequestParam UUID scwikiUuid,
      @RequestParam long version) {
    syncReportClient.chooseManufacturerWikiPrimary(
        manufacturerId, new ManufacturerWikiPrimaryRequest(scwikiUuid, version));
    return ResponseEntity.noContent().build();
  }
}
