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
 * Same-origin proxy for the admin-only squadron toggles (promotion feature and profit eligibility),
 * relayed via {@link BackendApiClient}.
 *
 * <p>Each toggle evicts the {@code CacheDomain.SQUADRON} and {@code CacheDomain.ORG_UNIT} caches
 * (REQ-DATA-007). The backend re-checks the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/proxy/squadrons")
@RequiredArgsConstructor
public class SquadronAdminProxyController {

  private final BackendApiClient backendApiClient;

  /**
   * Forwards a request to set a squadron's promotion-enabled flag.
   *
   * @param id squadron primary key
   * @param body request payload {@code { "enabled": true|false }}
   * @return 204 No Content on success
   */
  @PatchMapping("/{id}/promotion-enabled")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Void> setPromotionEnabled(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    backendApiClient.patch("/api/v1/squadrons/" + id + "/promotion-enabled", body, Void.class);
    backendApiClient.evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    return ResponseEntity.noContent().build();
  }

  /**
   * Forwards a request to set a squadron's profit-eligible flag.
   *
   * @param id squadron primary key
   * @param body request payload {@code { "eligible": true|false }}
   * @return 204 No Content on success
   */
  @PatchMapping("/{id}/profit-eligible")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<Void> setProfitEligible(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    backendApiClient.patch("/api/v1/squadrons/" + id + "/profit-eligible", body, Void.class);
    backendApiClient.evict(CacheDomain.SQUADRON, CacheDomain.ORG_UNIT);
    return ResponseEntity.noContent().build();
  }
}
