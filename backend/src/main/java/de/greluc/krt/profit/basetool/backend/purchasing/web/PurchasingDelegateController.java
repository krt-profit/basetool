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

package de.greluc.krt.profit.basetool.backend.purchasing.web;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.AppointDelegateRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.DelegateDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingDelegateService;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingVersionRequest;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.UpdateDelegateLimitRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Handelsbeauftragten (REQ-PUR-003). The governing leadership and admin appoint and revoke them
 * and set their limits; the service enforces that circle.
 */
@RestController
@RequestMapping("/api/v1/purchasing/delegates")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class PurchasingDelegateController {

  private final PurchasingDelegateService delegateService;

  /**
   * Lists the appointments, newest first.
   *
   * @return the appointments with their limits
   */
  @Operation(summary = "List the trade delegates")
  @GetMapping
  public List<DelegateDto> list() {
    return delegateService.list();
  }

  /**
   * Appoints a member as Handelsbeauftragter.
   *
   * @param request the member, the end date and the personal limit
   * @return the appointment
   */
  @Operation(summary = "Appoint a trade delegate")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DelegateDto appoint(@NotNull @Valid @RequestBody AppointDelegateRequest request) {
    return delegateService.appoint(request);
  }

  /**
   * Revokes an appointment.
   *
   * @param id the appointment
   * @param request the echoed version
   * @return the revoked appointment
   */
  @Operation(summary = "Revoke a trade delegate")
  @PostMapping("/{id}/revoke")
  public DelegateDto revoke(
      @PathVariable UUID id, @NotNull @Valid @RequestBody PurchasingVersionRequest request) {
    return delegateService.revoke(id, request.version());
  }

  /**
   * Sets the personal monthly limit of a delegate.
   *
   * @param id the appointment
   * @param request the limit and the echoed version
   * @return the appointment
   */
  @Operation(summary = "Set the personal monthly limit of a trade delegate")
  @PutMapping("/{id}/limit")
  public DelegateDto setLimit(
      @PathVariable UUID id, @NotNull @Valid @RequestBody UpdateDelegateLimitRequest request) {
    return delegateService.setLimit(id, request);
  }
}
