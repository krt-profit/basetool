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

package de.greluc.krt.profit.basetool.backend.supply.web;

import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import de.greluc.krt.profit.basetool.backend.supply.api.DonationStatus;
import de.greluc.krt.profit.basetool.backend.supply.internal.DeclineDonationRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.DonationDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.DonationSourceDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.MyDonationStatsDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.NeedDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.PledgeRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.ReceiveDonationRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.RecordDonationRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyDonationService;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyStatsService;
import de.greluc.krt.profit.basetool.backend.supply.internal.VersionRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The material donations (REQ-SUP-007 … REQ-SUP-012): a member pledges, the receiving unit confirms
 * or declines, and a unit records a donation it already received. The role checks of the receiving
 * unit live in {@link SupplyDonationService}.
 */
@RestController
@RequestMapping("/api/v1/supply")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class SupplyDonationController {

  private static final Set<String> SORT_FIELDS = Set.of("createdAt", "pledgedAmount", "status");

  private final SupplyDonationService donationService;
  private final SupplyStatsService statsService;

  /**
   * Pages over the donations a unit received or was pledged.
   *
   * @param orgUnitId the receiving unit
   * @param status only these states
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of donations
   */
  @Operation(summary = "List the donations of a receiving unit (paged)")
  @GetMapping("/donations")
  public PageResponse<DonationDto> listForUnit(
      @RequestParam UUID orgUnitId,
      @RequestParam(required = false) Set<DonationStatus> status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return PageResponse.of(
        donationService.listForUnit(
            orgUnitId, status == null ? Set.of() : status, pageable(page, size, sort)));
  }

  /**
   * Pledges a donation.
   *
   * @param request the pledge
   * @return the pledged donation
   */
  @Operation(summary = "Pledge a material donation")
  @PostMapping("/donations")
  @ResponseStatus(HttpStatus.CREATED)
  public DonationDto pledge(@NotNull @Valid @RequestBody PledgeRequest request) {
    return donationService.pledge(request);
  }

  /**
   * Records a donation the unit already received.
   *
   * @param request the received donation
   * @return the recorded donation
   */
  @Operation(summary = "Record a donation the unit already received")
  @PostMapping("/donations/recorded")
  @ResponseStatus(HttpStatus.CREATED)
  public DonationDto record(@NotNull @Valid @RequestBody RecordDonationRequest request) {
    return donationService.record(request);
  }

  /**
   * Confirms the receipt of a pledge.
   *
   * @param id the donation
   * @param request the received amount, the location and the echoed version
   * @return the received donation
   */
  @Operation(summary = "Confirm the receipt of a pledged donation")
  @PostMapping("/donations/{id}/receive")
  public DonationDto receive(
      @PathVariable UUID id, @NotNull @Valid @RequestBody ReceiveDonationRequest request) {
    return donationService.receive(id, request);
  }

  /**
   * Declines a pledge.
   *
   * @param id the donation
   * @param request the reason and the echoed version
   * @return the declined donation
   */
  @Operation(summary = "Decline a pledged donation")
  @PostMapping("/donations/{id}/decline")
  public DonationDto decline(
      @PathVariable UUID id, @NotNull @Valid @RequestBody DeclineDonationRequest request) {
    return donationService.decline(id, request);
  }

  /**
   * Withdraws a pledge.
   *
   * @param id the donation
   * @param request the echoed version
   * @return the withdrawn donation
   */
  @Operation(summary = "Withdraw a pledged donation")
  @PostMapping("/donations/{id}/withdraw")
  public DonationDto withdraw(
      @PathVariable UUID id, @NotNull @Valid @RequestBody VersionRequest request) {
    return donationService.withdraw(id, request.version());
  }

  /**
   * Pages over the caller's own donations.
   *
   * @param status only these states
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of donations
   */
  @Operation(summary = "List the caller's own donations (paged)")
  @GetMapping("/me/donations")
  public PageResponse<DonationDto> listMine(
      @RequestParam(required = false) Set<DonationStatus> status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return PageResponse.of(
        donationService.listMine(status == null ? Set.of() : status, pageable(page, size, sort)));
  }

  /**
   * Reads the caller's own received donations.
   *
   * @return the totals
   */
  @Operation(summary = "Read the caller's own donation totals")
  @GetMapping("/me/donations/stats")
  public MyDonationStatsDto myStats() {
    return statsService.mine();
  }

  /**
   * Lists the caller's own Lager rows a pledge can be made from, for the pledge form's picker.
   *
   * @param needId the need the pledge credits
   * @param materialId the material of a free pledge
   * @param gameItemId the game item of a free pledge
   * @return the rows, best quality first
   */
  @Operation(summary = "List the caller's own Lager rows a pledge can be made from")
  @GetMapping("/me/donation-sources")
  public List<DonationSourceDto> mySources(
      @RequestParam(required = false) UUID needId,
      @RequestParam(required = false) UUID materialId,
      @RequestParam(required = false) UUID gameItemId) {
    return donationService.sources(needId, materialId, gameItemId);
  }

  /**
   * Lists the open needs a pledge from one of the caller's Lager rows can credit, for the need
   * picker of the Lager's donation dialog.
   *
   * @param rowId the caller's Lager row
   * @return the fitting open needs, oldest first
   */
  @Operation(
      summary = "List the open needs a pledge from one of the caller's Lager rows can credit")
  @GetMapping("/me/donation-targets")
  public List<NeedDto> myTargets(@RequestParam UUID rowId) {
    return donationService.targets(rowId);
  }

  private static @NotNull Pageable pageable(Integer page, Integer size, String sort) {
    return PaginationUtil.createPageRequest(
        page,
        size,
        sort == null || sort.isBlank() ? "createdAt,desc" : sort,
        SORT_FIELDS,
        "createdAt");
  }
}
