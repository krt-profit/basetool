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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.inventory.client.StockLevelBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelCreateRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelUpdateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX proxies of the „Mindestbestände" tab that set, change and remove a minimum stock level,
 * relaying the backend status and RFC 7807 {@code code}; the backend decides every gate
 * (REQ-INV-068).
 */
@RestController
@RequestMapping("/inventory/stock-levels")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class StockLevelProxyController {

  private final StockLevelBackendClient stockLevelClient;

  /**
   * Forwards a new level.
   *
   * @param request the unit, material, tier and amounts
   * @return {@code 201} with the level, or the relayed backend error
   */
  @PostMapping
  public ResponseEntity<Object> create(
      @RequestBody @Valid @NotNull StockLevelCreateRequest request) {
    return relay(
        log,
        "Set minimum stock level",
        () -> ResponseEntity.status(HttpStatus.CREATED).body(stockLevelClient.create(request)));
  }

  /**
   * Forwards a change of a level.
   *
   * @param id the level
   * @param request the new values and the echoed version
   * @return {@code 200} with the level, or the relayed backend error
   */
  @PutMapping("/{id}")
  public ResponseEntity<Object> update(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull StockLevelUpdateRequest request) {
    return relay(
        log,
        "Change minimum stock level",
        () -> ResponseEntity.ok(stockLevelClient.update(id, request)));
  }

  /**
   * Forwards the removal of a level.
   *
   * @param id the level
   * @param version the echoed version
   * @return {@code 204}, or the relayed backend error
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Object> delete(@PathVariable @NotNull UUID id, @RequestParam long version) {
    return relay(
        log,
        "Remove minimum stock level",
        () -> {
          stockLevelClient.delete(id, version);
          return ResponseEntity.noContent().build();
        });
  }
}
