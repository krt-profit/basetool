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

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.LinkedStockAttributionDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX relay of the per-row quality-bucket attribution of one order material (REQ-ORDERS-037), so
 * the order detail lists each linked row under the bucket it counts toward.
 */
@RestController
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class JobOrderStockAttributionProxyController {

  /** Reads the attribution from the backend. */
  private final JobOrderBackendClient jobOrderClient;

  /**
   * Forwards to {@code GET /api/v1/orders/{id}/materials/{matId}/attribution}.
   *
   * @param id the job order
   * @param matId the material
   * @return {@code 200} with one entry per row and bucket, or {@code 502} when the backend did not
   *     answer
   */
  @NotNull
  @GetMapping(
      value = "/{id}/materials/{matId}/attribution",
      headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<List<LinkedStockAttributionDto>> stockAttribution(
      @PathVariable UUID id, @PathVariable UUID matId) {
    try {
      return ResponseEntity.ok(jobOrderClient.stockAttribution(id, matId));
    } catch (Exception e) {
      log.warn("Failed to load stock attribution for job order {} and material {}", id, matId, e);
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
  }
}
