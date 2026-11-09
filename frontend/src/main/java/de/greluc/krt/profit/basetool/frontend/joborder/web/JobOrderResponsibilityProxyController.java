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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ReassignResponsibleOrgUnitRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Relays current-authority responsibility transfers and queue invalidations. */
@RestController
@RequestMapping("/orders")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Slf4j
public class JobOrderResponsibilityProxyController {

  private static final List<String> ORDERS_QUEUE_SECTION = List.of("queue");

  private final JobOrderBackendClient jobOrderClient;
  private final LiveSyncLocalBus liveSyncLocalBus;

  /**
   * Relays a responsibility transfer under the backend's current writer or recovery authority.
   *
   * @param id the order
   * @param request the valid target unit
   * @return the updated order or the backend error
   */
  @PatchMapping("/{id}/responsible-org-unit")
  public ResponseEntity<Object> transferResponsibility(
      @PathVariable UUID id, @RequestBody @Valid ReassignResponsibleOrgUnitRequest request) {
    return relay(
        log,
        "transfer order responsibility",
        () -> {
          JobOrderDto order = jobOrderClient.transferResponsibility(id, request);
          liveSyncLocalBus.publish("orders", ORDERS_QUEUE_SECTION);
          return ResponseEntity.ok(order);
        });
  }
}
