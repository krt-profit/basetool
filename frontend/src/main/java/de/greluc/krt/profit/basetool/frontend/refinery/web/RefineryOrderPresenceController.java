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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.refinery.client.RefineryBackendClient;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers whether the caller can still read a refinery order, so the detail page confirms a {@code
 * deleted} live-sync signal with the server before it locks itself (REQ-XCH-055).
 */
@RestController
@RequestMapping("/refinery-orders")
@RequiredArgsConstructor
public class RefineryOrderPresenceController {

  /** The refinery domain's typed backend client. */
  private final RefineryBackendClient refineryClient;

  /**
   * Reads the order through the backend's own gate.
   *
   * @param id the refinery order id
   * @return 204 when the caller can read the order, otherwise the backend's status and problem body
   *     (404 or 403 once the order is gone for the caller)
   */
  @NotNull
  @GetMapping("/{id}/presence")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Object> presence(@PathVariable @NotNull UUID id) {
    try {
      refineryClient.order(id);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      return propagateBackendError(e);
    }
  }
}
