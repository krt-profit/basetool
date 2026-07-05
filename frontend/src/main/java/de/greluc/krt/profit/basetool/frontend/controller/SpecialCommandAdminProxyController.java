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
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.Map;
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
 * Same-origin proxy for the admin-only Spezialkommando toggle endpoints. Currently exposes only the
 * per-SK profit-eligibility flip; the admin-settings page sends a PATCH here when the admin clicks
 * the "Auftragsbearbeitung" checkbox for an SK so the browser never has to know the backend
 * hostname and the CSRF-protected session is reused via {@link BackendApiClient}. Mirrors {@link
 * SquadronAdminProxyController}.
 *
 * <p>Endpoints carry their own {@code ADMIN}-role gate at the Spring Security layer; the backend
 * re-checks the role, so this proxy is defence-in-depth and not the sole guard.
 */
@RestController
@RequestMapping("/api/proxy/special-commands")
@RequiredArgsConstructor
public class SpecialCommandAdminProxyController {

  private final BackendApiClient backendApiClient;

  /**
   * Forwards a "set Spezialkommando profit-eligible flag" request to the backend. Returns 204 No
   * Content on success so the AJAX caller does not have to parse the SK payload — it already knows
   * the new state from the checkbox event.
   *
   * @param id Spezialkommando primary key.
   * @param body request payload {@code { "eligible": true|false }}.
   * @return 204 No Content on success.
   */
  @PatchMapping("/{id}/profit-eligible")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Void> setProfitEligible(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    backendApiClient.patch("/api/v1/special-commands/" + id + "/profit-eligible", body, Void.class);
    // isProfitEligible is carried on the cached org-units owner-picker options and the admin
    // switcher's SK catalogue, so the toggle must evict STATIC_DATA_CACHE to keep them truthful
    // (REQ-DATA-007 — same eviction gate as SquadronAdminProxyController).
    backendApiClient.evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    return ResponseEntity.noContent().build();
  }
}
