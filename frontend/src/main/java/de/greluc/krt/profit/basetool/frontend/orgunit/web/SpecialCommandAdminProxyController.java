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

package de.greluc.krt.profit.basetool.frontend.orgunit.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandProfitEligibleToggleRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same-origin proxy for the admin-only Spezialkommando toggles, currently the profit-eligibility
 * flag, relayed via {@link OrgUnitBackendClient}; the counterpart of {@link
 * SquadronAdminProxyController}. The backend re-checks the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/proxy/special-commands")
@RequiredArgsConstructor
public class SpecialCommandAdminProxyController {

  /** Sends the toggle to the backend. */
  private final OrgUnitBackendClient orgUnitClient;

  /** Drops the cached org-unit catalogues after the toggle. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Forwards a request to set a Spezialkommando's profit-eligible flag.
   *
   * @param id Spezialkommando primary key.
   * @param body request payload {@code { "eligible": true|false }}.
   * @return 204 No Content on success.
   */
  @PatchMapping("/{id}/profit-eligible")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Void> setProfitEligible(
      @PathVariable @NotNull UUID id,
      @RequestBody @NotNull SpecialCommandProfitEligibleToggleRequest body) {
    orgUnitClient.setSpecialCommandProfitEligible(id, body);
    cacheEviction.evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    return ResponseEntity.noContent().build();
  }
}
