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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.model.dto.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import de.greluc.krt.profit.basetool.frontend.support.StringNormalization;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the quality-tier admin page's create, update and delete to {@code
 * /api/v1/admin/quality-tiers} (REQ-ORDERS-036); a backend refusal is relayed as {@code
 * application/problem+json} with the backend's status and problem code.
 *
 * <p>Every successful write evicts {@link CacheDomain#QUALITY_TIER} so the order pages pick the
 * change up at once.
 */
@RestController
@RequestMapping("/admin/quality-tiers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminQualityTiersRelayController {

  /** The backend's admin quality-tier endpoints. */
  static final String BACKEND_BASE = "/api/v1/admin/quality-tiers";

  /** Talks to the backend. */
  private final BackendApiClient backendApiClient;

  /**
   * Creates a tier and returns it; a backend refusal ({@code 400}, {@code 409}) is relayed with its
   * status and problem code.
   *
   * @param request the create payload; its {@code version} is ignored
   * @return the created tier, the relayed backend status on failure, or {@code 500} on an
   *     unexpected error
   */
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> create(@RequestBody @NotNull QualityTierWriteDto request) {
    return relay(
        log,
        "create quality tier",
        () -> {
          QualityTierDto created =
              backendApiClient.post(BACKEND_BASE, normalized(request, null), QualityTierDto.class);
          backendApiClient.evict(CacheDomain.QUALITY_TIER);
          return ResponseEntity.ok(created);
        });
  }

  /**
   * Updates a tier and returns it with its fresh version; a stale version, a duplicate code or
   * floor, or a changed floor of a used tier is relayed as the backend's {@code 409}.
   *
   * @param id the tier to update
   * @param request the update payload, including {@code version}
   * @return the updated tier, the relayed backend status on failure, or {@code 500} on an
   *     unexpected error
   */
  @PostMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> update(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull QualityTierWriteDto request) {
    return relay(
        log,
        "update quality tier " + id,
        () -> {
          QualityTierDto updated =
              backendApiClient.put(
                  BACKEND_BASE + "/{id}",
                  normalized(request, request.version()),
                  QualityTierDto.class,
                  id);
          backendApiClient.evict(CacheDomain.QUALITY_TIER);
          return ResponseEntity.ok(updated);
        });
  }

  /**
   * Deletes an unused tier; a tier in use or the base tier is relayed as the backend's {@code 409}.
   *
   * @param id the tier to delete
   * @return {@code 200} on success, the relayed backend status on failure, or {@code 500} on an
   *     unexpected error
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> delete(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "delete quality tier " + id,
        () -> {
          backendApiClient.delete(BACKEND_BASE + "/{id}", Void.class, id);
          backendApiClient.evict(CacheDomain.QUALITY_TIER);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Trims the text components and upper-cases the code before the payload goes to the backend.
   *
   * @param request the browser's payload
   * @param version the version to send, {@code null} on create
   * @return the payload the backend receives
   */
  @NotNull
  private static QualityTierWriteDto normalized(
      @NotNull QualityTierWriteDto request, @Nullable Long version) {
    String code = StringNormalization.trimToNull(request.code());
    return new QualityTierWriteDto(
        code == null ? null : code.toUpperCase(Locale.ROOT),
        request.minQuality(),
        StringNormalization.trimToNull(request.labelDe()),
        StringNormalization.trimToNull(request.labelEn()),
        request.sortOrder(),
        request.active(),
        version);
  }
}
