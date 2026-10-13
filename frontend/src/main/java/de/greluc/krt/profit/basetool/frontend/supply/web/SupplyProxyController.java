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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient.NeedAction;
import de.greluc.krt.profit.basetool.frontend.supply.model.CreateNeedRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.DeclineDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.DonationDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.PledgeRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.ReceiveDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.RecordDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplySettingsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateNeedRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateSupplySettingsRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateSupplyUnitRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.VersionRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX proxy for every supply mutation ({@code /api/proxy/supply/**}), forwarding the JSON body to
 * the matching {@code /api/v1/supply/**} endpoint. Backend RFC 7807 errors reach the browser as
 * localized JSON; all authorization is decided by the backend.
 */
@RestController
@RequestMapping("/api/proxy/supply")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SupplyProxyController {

  /** The supply domain's backend calls. */
  private final SupplyBackendClient supplyClient;

  /**
   * Forwards a new need.
   *
   * @param request the need
   * @return the raised need
   */
  @PostMapping("/needs")
  @ResponseStatus(HttpStatus.CREATED)
  public NeedDto createNeed(@RequestBody @Valid @NotNull CreateNeedRequest request) {
    return supplyClient.createNeed(request);
  }

  /**
   * Forwards a need edit.
   *
   * @param id the need
   * @param request the new values and the echoed version
   * @return the edited need
   */
  @PutMapping("/needs/{id}")
  public NeedDto updateNeed(
      @PathVariable UUID id, @RequestBody @Valid @NotNull UpdateNeedRequest request) {
    return supplyClient.updateNeed(id, request);
  }

  /**
   * Forwards the withdrawal of a need.
   *
   * @param id the need
   * @param request the echoed version
   * @return the withdrawn need
   */
  @PostMapping("/needs/{id}/withdraw")
  public NeedDto withdrawNeed(
      @PathVariable UUID id, @RequestBody @Valid @NotNull VersionRequest request) {
    return supplyClient.changeNeed(id, NeedAction.WITHDRAW, request);
  }

  /**
   * Forwards the pause of a need.
   *
   * @param id the need
   * @param request the echoed version
   * @return the paused need
   */
  @PostMapping("/needs/{id}/pause")
  public NeedDto pauseNeed(
      @PathVariable UUID id, @RequestBody @Valid @NotNull VersionRequest request) {
    return supplyClient.changeNeed(id, NeedAction.PAUSE, request);
  }

  /**
   * Forwards the resumption of a need.
   *
   * @param id the need
   * @param request the echoed version
   * @return the reopened need
   */
  @PostMapping("/needs/{id}/resume")
  public NeedDto resumeNeed(
      @PathVariable UUID id, @RequestBody @Valid @NotNull VersionRequest request) {
    return supplyClient.changeNeed(id, NeedAction.RESUME, request);
  }

  /**
   * Forwards a pledge.
   *
   * @param request the pledge
   * @return the pledged donation
   */
  @PostMapping("/donations")
  @ResponseStatus(HttpStatus.CREATED)
  public DonationDto pledge(@RequestBody @Valid @NotNull PledgeRequest request) {
    return supplyClient.pledge(request);
  }

  /**
   * Forwards a recorded donation.
   *
   * @param request the received donation
   * @return the recorded donation
   */
  @PostMapping("/donations/recorded")
  @ResponseStatus(HttpStatus.CREATED)
  public DonationDto record(@RequestBody @Valid @NotNull RecordDonationRequest request) {
    return supplyClient.record(request);
  }

  /**
   * Forwards the receipt of a pledge.
   *
   * @param id the donation
   * @param request the received amount, the location and the echoed version
   * @return the received donation
   */
  @PostMapping("/donations/{id}/receive")
  public DonationDto receive(
      @PathVariable UUID id, @RequestBody @Valid @NotNull ReceiveDonationRequest request) {
    return supplyClient.receive(id, request);
  }

  /**
   * Forwards the decline of a pledge.
   *
   * @param id the donation
   * @param request the reason and the echoed version
   * @return the declined donation
   */
  @PostMapping("/donations/{id}/decline")
  public DonationDto decline(
      @PathVariable UUID id, @RequestBody @Valid @NotNull DeclineDonationRequest request) {
    return supplyClient.decline(id, request);
  }

  /**
   * Forwards the withdrawal of a pledge.
   *
   * @param id the donation
   * @param request the echoed version
   * @return the withdrawn donation
   */
  @PostMapping("/donations/{id}/withdraw")
  public DonationDto withdraw(
      @PathVariable UUID id, @RequestBody @Valid @NotNull VersionRequest request) {
    return supplyClient.withdraw(id, request);
  }

  /**
   * Forwards what a unit does in the supply module.
   *
   * @param orgUnitId the unit
   * @param request the flags and the echoed version
   * @return the unit with its new setting
   */
  @PutMapping("/admin/units/{orgUnitId}")
  @PreAuthorize("hasRole('ADMIN')")
  public SupplyUnitDto updateUnit(
      @PathVariable UUID orgUnitId, @RequestBody @Valid @NotNull UpdateSupplyUnitRequest request) {
    return supplyClient.updateUnit(orgUnitId, request);
  }

  /**
   * Forwards the runtime settings.
   *
   * @param request the switch and the governing Bereich
   * @return the stored settings
   */
  @PutMapping("/admin/settings")
  @PreAuthorize("hasRole('ADMIN')")
  public SupplySettingsDto updateSettings(
      @RequestBody @Valid @NotNull UpdateSupplySettingsRequest request) {
    return supplyClient.updateSettings(request);
  }
}
