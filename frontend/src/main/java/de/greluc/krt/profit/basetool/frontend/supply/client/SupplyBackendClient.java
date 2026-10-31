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

package de.greluc.krt.profit.basetool.frontend.supply.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.supply.model.CreateNeedRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.DeclineDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.DonationDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.MyDonationStatsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedJobOrderOptionDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.PledgeRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.ReceiveDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.RecordDonationRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplySettingsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStatsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateNeedRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateSupplySettingsRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.UpdateSupplyUnitRequest;
import de.greluc.krt.profit.basetool.frontend.supply.model.VersionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the supply domain: the needs, the donations, the statistics and the admin
 * settings, over {@link BackendApiClient} (REQ-SUP-001 … REQ-SUP-020).
 */
@Service
@RequiredArgsConstructor
public class SupplyBackendClient {

  private static final String NEEDS = "/api/v1/supply/needs";
  private static final String DONATIONS = "/api/v1/supply/donations";

  private static final ParameterizedTypeReference<PageResponse<NeedDto>> NEED_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<NeedDto>> NEED_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<NeedJobOrderOptionDto>>
      JOB_ORDER_OPTION_LIST = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<PageResponse<DonationDto>> DONATION_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<SupplyUnitDto>> UNIT_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<MaterialReferenceDto>> MATERIAL_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<LocationReferenceDto>> LOCATION_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<InventoryGameItemReferenceDto>>
      ITEM_PAGE = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads whether the supply surface is switched on.
   *
   * @return the switch, or {@code null} when the backend sent no body
   */
  @Nullable
  public SupplyStateDto state() {
    return backendApiClient.get("/api/v1/supply/state", SupplyStateDto.class);
  }

  /**
   * Reads one page of the needs the caller may see.
   *
   * @param orgUnitId only this raising unit, or {@code null}
   * @param status only this state, or {@code null} for the visible ones
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<NeedDto> needs(
      @Nullable UUID orgUnitId, @Nullable String status, int page, int size) {
    StringBuilder template =
        new StringBuilder("/api/v1/supply/needs?page={page}&size={size}&sort=createdAt,desc");
    List<Object> variables = new ArrayList<>(List.of(page, size));
    if (orgUnitId != null) {
      template.append("&orgUnitId={orgUnitId}");
      variables.add(orgUnitId);
    }
    if (status != null && !status.isBlank()) {
      template.append("&status={status}");
      variables.add(status);
    }
    return backendApiClient.get(template.toString(), NEED_PAGE, variables.toArray());
  }

  /**
   * Reads the open needs a trade delegate may buy for, for the purchasing deal form.
   *
   * @return the first page of open purchasable needs, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<NeedDto> purchasableNeeds() {
    return backendApiClient.get(
        "/api/v1/supply/needs?status=OPEN&purchasable=true&size=200&sort=createdAt,desc",
        NEED_PAGE);
  }

  /**
   * Reads the open needs of one unit, for the pledge form.
   *
   * @param orgUnitId the raising unit
   * @return the open needs, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<NeedDto> openNeeds(@NotNull UUID orgUnitId) {
    return backendApiClient.get(NEEDS + "/open?orgUnitId={id}", NEED_LIST, orgUnitId);
  }

  /**
   * Reads the job orders the caller may link a need to, for the need form.
   *
   * @return the open and in-progress orders of the managed units, or {@code null} when the backend
   *     sent no body
   */
  @Nullable
  public List<NeedJobOrderOptionDto> needJobOrders() {
    return backendApiClient.get(NEEDS + "/job-orders", JOB_ORDER_OPTION_LIST);
  }

  /**
   * Raises a need.
   *
   * @param request the need
   * @return the raised need
   */
  @Nullable
  public NeedDto createNeed(@NotNull CreateNeedRequest request) {
    return backendApiClient.post(NEEDS, request, NeedDto.class);
  }

  /**
   * Edits a need.
   *
   * @param id the need
   * @param request the new values and the echoed version
   * @return the edited need
   */
  @Nullable
  public NeedDto updateNeed(@NotNull UUID id, @NotNull UpdateNeedRequest request) {
    return backendApiClient.put(NEEDS + "/{id}", request, NeedDto.class, id);
  }

  /**
   * Changes the state of a need: {@code withdraw}, {@code pause} or {@code resume}.
   *
   * @param id the need
   * @param action the state change
   * @param request the echoed version
   * @return the changed need
   */
  @Nullable
  public NeedDto changeNeed(
      @NotNull UUID id, @NotNull NeedAction action, @NotNull VersionRequest request) {
    String template =
        switch (action) {
          case WITHDRAW -> "/api/v1/supply/needs/{id}/withdraw";
          case PAUSE -> "/api/v1/supply/needs/{id}/pause";
          case RESUME -> "/api/v1/supply/needs/{id}/resume";
        };
    return backendApiClient.post(template, request, NeedDto.class, id);
  }

  /**
   * Reads one page of the donations a unit received or was pledged.
   *
   * @param orgUnitId the receiving unit
   * @param status only this state, or {@code null} for all
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<DonationDto> donationsOfUnit(
      @NotNull UUID orgUnitId, @Nullable String status, int page, int size) {
    StringBuilder template =
        new StringBuilder("/api/v1/supply/donations?orgUnitId={orgUnitId}&page={page}&size={size}");
    List<Object> variables = new ArrayList<>(List.of(orgUnitId, page, size));
    if (status != null && !status.isBlank()) {
      template.append("&status={status}");
      variables.add(status);
    }
    return backendApiClient.get(template.toString(), DONATION_PAGE, variables.toArray());
  }

  /**
   * Reads one page of the caller's own donations.
   *
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<DonationDto> myDonations(int page, int size) {
    return backendApiClient.get(
        "/api/v1/supply/me/donations?page={page}&size={size}", DONATION_PAGE, page, size);
  }

  /**
   * Reads the caller's own donation totals.
   *
   * @return the totals, or {@code null} when the backend sent no body
   */
  @Nullable
  public MyDonationStatsDto myStats() {
    return backendApiClient.get("/api/v1/supply/me/donations/stats", MyDonationStatsDto.class);
  }

  /**
   * Pledges a donation.
   *
   * @param request the pledge
   * @return the pledged donation
   */
  @Nullable
  public DonationDto pledge(@NotNull PledgeRequest request) {
    return backendApiClient.post(DONATIONS, request, DonationDto.class);
  }

  /**
   * Records a donation the unit already received.
   *
   * @param request the received donation
   * @return the recorded donation
   */
  @Nullable
  public DonationDto record(@NotNull RecordDonationRequest request) {
    return backendApiClient.post(DONATIONS + "/recorded", request, DonationDto.class);
  }

  /**
   * Confirms the receipt of a pledge.
   *
   * @param id the donation
   * @param request the received amount, the location and the echoed version
   * @return the received donation
   */
  @Nullable
  public DonationDto receive(@NotNull UUID id, @NotNull ReceiveDonationRequest request) {
    return backendApiClient.post(DONATIONS + "/{id}/receive", request, DonationDto.class, id);
  }

  /**
   * Declines a pledge.
   *
   * @param id the donation
   * @param request the reason and the echoed version
   * @return the declined donation
   */
  @Nullable
  public DonationDto decline(@NotNull UUID id, @NotNull DeclineDonationRequest request) {
    return backendApiClient.post(DONATIONS + "/{id}/decline", request, DonationDto.class, id);
  }

  /**
   * Withdraws a pledge.
   *
   * @param id the donation
   * @param request the echoed version
   * @return the withdrawn donation
   */
  @Nullable
  public DonationDto withdraw(@NotNull UUID id, @NotNull VersionRequest request) {
    return backendApiClient.post(DONATIONS + "/{id}/withdraw", request, DonationDto.class, id);
  }

  /**
   * Reads the donation statistics of the last months.
   *
   * @param months how many months back
   * @return the statistics, or {@code null} when the backend sent no body
   */
  @Nullable
  public SupplyStatsDto stats(int months) {
    return backendApiClient.get(
        "/api/v1/supply/stats?months={months}", SupplyStatsDto.class, months);
  }

  /**
   * Reads the units that raise needs or accept donations.
   *
   * @return the units, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<SupplyUnitDto> units() {
    return backendApiClient.get("/api/v1/supply/units", UNIT_LIST);
  }

  /**
   * Reads every active unit with its supply setting.
   *
   * @return the units, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<SupplyUnitDto> adminUnits() {
    return backendApiClient.get("/api/v1/supply/admin/units", UNIT_LIST);
  }

  /**
   * Sets what a unit does in the supply module.
   *
   * @param orgUnitId the unit
   * @param request the flags and the echoed version
   * @return the unit with its new setting
   */
  @Nullable
  public SupplyUnitDto updateUnit(
      @NotNull UUID orgUnitId, @NotNull UpdateSupplyUnitRequest request) {
    return backendApiClient.put(
        "/api/v1/supply/admin/units/{id}", request, SupplyUnitDto.class, orgUnitId);
  }

  /**
   * Reads the runtime settings.
   *
   * @return the switch and the governing Bereich, or {@code null} when the backend sent no body
   */
  @Nullable
  public SupplySettingsDto settings() {
    return backendApiClient.get("/api/v1/supply/admin/settings", SupplySettingsDto.class);
  }

  /**
   * Stores the runtime settings.
   *
   * @param request the switch and the governing Bereich
   * @return the stored settings
   */
  @Nullable
  public SupplySettingsDto updateSettings(@NotNull UpdateSupplySettingsRequest request) {
    return backendApiClient.put("/api/v1/supply/admin/settings", request, SupplySettingsDto.class);
  }

  /**
   * Reads the cached material lookup catalogue.
   *
   * @return the materials, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<MaterialReferenceDto> materials() {
    return backendApiClient.getCached(CachedCatalog.MATERIALS_LOOKUP, MATERIAL_LIST);
  }

  /**
   * Reads the cached location lookup catalogue.
   *
   * @return the locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<LocationReferenceDto> locations() {
    return backendApiClient.getCached(CachedCatalog.LOCATIONS_LOOKUP, LOCATION_LIST);
  }

  /**
   * Reads the first page of the item catalogue, sorted by name.
   *
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<InventoryGameItemReferenceDto> items() {
    return backendApiClient.get("/api/v1/game-items?size=500&sort=name,asc", ITEM_PAGE);
  }

  /** The state changes of a need that carry only the echoed version. */
  public enum NeedAction {

    /** Withdraws the need. */
    WITHDRAW,

    /** Pauses the need. */
    PAUSE,

    /** Opens the need again. */
    RESUME
  }
}
