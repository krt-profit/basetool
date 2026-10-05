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

import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryMergeCandidatesDto;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the Einbuchen merge probe (REQ-INV-026), so the form offers the {@code SCU} merge
 * opt-in only when a row to merge with exists.
 */
@RestController
@RequestMapping("/inventory")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class InventoryMergeProbeProxyController {

  /** The inventory domain's typed backend client. */
  private final InventoryBackendClient inventoryClient;

  /**
   * Forwards the probe to {@code GET /api/v1/inventory/merge-candidates}.
   *
   * @param userId the member booked for, or {@code null} for the caller
   * @param materialId the material being booked in
   * @param locationId the target location
   * @param quality the quality grade
   * @param personal whether the row would be personal
   * @param stolen whether the row would be marked „gestohlen"
   * @param owningOrgUnitId the picked owning org unit, or {@code null} for the auto-stamp
   * @return {@code 200} with {@code {exists}}, or {@code 502} when the backend did not answer
   */
  @NotNull
  @GetMapping(value = "/merge-candidates", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Map<String, Object>> mergeCandidates(
      @RequestParam(required = false) UUID userId,
      @RequestParam UUID materialId,
      @RequestParam UUID locationId,
      @RequestParam int quality,
      @RequestParam(required = false, defaultValue = "false") boolean personal,
      @RequestParam(required = false, defaultValue = "false") boolean stolen,
      @RequestParam(required = false) UUID owningOrgUnitId) {
    try {
      InventoryMergeCandidatesDto answer =
          inventoryClient.mergeCandidates(
              userId, materialId, locationId, quality, personal, stolen, owningOrgUnitId);
      return ResponseEntity.ok(Map.of("exists", answer != null && answer.exists()));
    } catch (Exception e) {
      log.warn("Failed to probe inventory merge candidates", e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
  }
}
