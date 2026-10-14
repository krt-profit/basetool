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

package de.greluc.krt.profit.basetool.frontend.purchasing.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AcceptDeliveryRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AppointDelegateRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.CreateDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.CreatePartnerRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DelegateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.FundingAccountOptionDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PausePurchasingRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PauseStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingReportDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingSettingsDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingVersionRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RecordDeliveryRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RequestTrancheRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.ResumePurchasingRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RiskClassDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.SettleDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateDelegateLimitRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdatePartnerRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdatePurchasingSettingsRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateRiskClassesRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the purchasing domain: the deals, partners, delegates, pause, report and
 * admin settings, over {@link BackendApiClient} (REQ-PUR-001 … REQ-PUR-028).
 */
@Service
@RequiredArgsConstructor
public class PurchasingBackendClient {

  private static final String DEALS = "/api/v1/purchasing/deals";
  private static final String PARTNERS = "/api/v1/purchasing/partners";
  private static final String DELEGATES = "/api/v1/purchasing/delegates";
  private static final String PAUSE = "/api/v1/purchasing/pause";
  private static final String REPORT = "/api/v1/purchasing/report";
  private static final String ADMIN = "/api/v1/purchasing/admin";

  private static final ParameterizedTypeReference<PageResponse<DealDto>> DEAL_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<PartnerDto>> PARTNER_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<DelegateDto>> DELEGATE_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<RiskClassDto>> RISK_CLASS_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<LocationReferenceDto>> LOCATION_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<FundingAccountOptionDto>> ACCOUNT_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>> UNIT_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads whether the purchasing surface is switched on and what the caller may do.
   *
   * @return the state, or {@code null} when the backend sent no body
   */
  @Nullable
  public PurchasingStateDto state() {
    return backendApiClient.get("/api/v1/purchasing/state", PurchasingStateDto.class);
  }

  /**
   * Reads one page of the deals the caller may read.
   *
   * @param status only this state, or {@code null}
   * @param mine only the caller's own deals
   * @param overdue only the deals whose settlement is overdue
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<DealDto> deals(
      @Nullable String status, boolean mine, boolean overdue, int page, int size) {
    StringBuilder template =
        new StringBuilder(DEALS + "?page={page}&size={size}&sort=createdAt,desc");
    List<Object> variables = new ArrayList<>(List.of(page, size));
    if (status != null && !status.isBlank()) {
      template.append("&status={status}");
      variables.add(status);
    }
    if (mine) {
      template.append("&mine=true");
    }
    if (overdue) {
      template.append("&overdue=true");
    }
    return backendApiClient.get(template.toString(), DEAL_PAGE, variables.toArray());
  }

  /**
   * Reads one deal.
   *
   * @param id the deal
   * @return the deal, or {@code null} when the backend sent no body
   */
  @Nullable
  public DealDto deal(@NotNull UUID id) {
    return backendApiClient.get(DEALS + "/{id}", DealDto.class, id);
  }

  /**
   * Creates a draft deal.
   *
   * @param request the deal
   * @return the created draft
   */
  @Nullable
  public DealDto createDeal(@NotNull CreateDealRequest request) {
    return backendApiClient.post(DEALS, request, DealDto.class);
  }

  /**
   * Edits a draft deal.
   *
   * @param id the deal
   * @param request the new values and the echoed version
   * @return the edited draft
   */
  @Nullable
  public DealDto updateDeal(@NotNull UUID id, @NotNull UpdateDealRequest request) {
    return backendApiClient.put(DEALS + "/{id}", request, DealDto.class, id);
  }

  /**
   * Cancels a deal that holds no confirmed money.
   *
   * @param id the deal
   * @param request the echoed version
   * @return the cancelled deal
   */
  @Nullable
  public DealDto cancelDeal(@NotNull UUID id, @NotNull PurchasingVersionRequest request) {
    return backendApiClient.post(DEALS + "/{id}/cancel", request, DealDto.class, id);
  }

  /**
   * Requests a money tranche of a deal.
   *
   * @param id the deal
   * @param request kind and amount
   * @return the deal with the new tranche
   */
  @Nullable
  public DealDto requestTranche(@NotNull UUID id, @NotNull RequestTrancheRequest request) {
    return backendApiClient.post(DEALS + "/{id}/tranches", request, DealDto.class, id);
  }

  /**
   * Records a delivery of a deal.
   *
   * @param id the deal
   * @param request the delivered amount and location
   * @return the deal with the new delivery
   */
  @Nullable
  public DealDto recordDelivery(@NotNull UUID id, @NotNull RecordDeliveryRequest request) {
    return backendApiClient.post(DEALS + "/{id}/deliveries", request, DealDto.class, id);
  }

  /**
   * Decides a delivery of a deal.
   *
   * @param id the deal
   * @param deliveryId the delivery
   * @param request the accepted and rejected amounts, quality, location and echoed version
   * @return the deal after the decision
   */
  @Nullable
  public DealDto acceptDelivery(
      @NotNull UUID id, @NotNull UUID deliveryId, @NotNull AcceptDeliveryRequest request) {
    return backendApiClient.post(
        DEALS + "/{id}/deliveries/{deliveryId}/accept", request, DealDto.class, id, deliveryId);
  }

  /**
   * Closes the delivery of a deal.
   *
   * @param id the deal
   * @return the closed deal
   */
  @Nullable
  public DealDto closeDelivery(@NotNull UUID id) {
    return backendApiClient.post(DEALS + "/{id}/close-delivery", null, DealDto.class, id);
  }

  /**
   * Records the settlement of a deal.
   *
   * @param id the deal
   * @param request money paid, loss and the echoed settlement version
   * @return the deal with its settlement
   */
  @Nullable
  public DealDto settle(@NotNull UUID id, @NotNull SettleDealRequest request) {
    return backendApiClient.put(DEALS + "/{id}/settlement", request, DealDto.class, id);
  }

  /**
   * Reads one page of the partners.
   *
   * @param query a name fragment, or {@code null}
   * @param kind only this kind, or {@code null}
   * @param status only this status, or {@code null}
   * @param page zero-based page index
   * @param size page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<PartnerDto> partners(
      @Nullable String query, @Nullable String kind, @Nullable String status, int page, int size) {
    StringBuilder template = new StringBuilder(PARTNERS + "?page={page}&size={size}");
    List<Object> variables = new ArrayList<>(List.of(page, size));
    if (query != null && !query.isBlank()) {
      template.append("&query={query}");
      variables.add(query);
    }
    if (kind != null && !kind.isBlank()) {
      template.append("&kind={kind}");
      variables.add(kind);
    }
    if (status != null && !status.isBlank()) {
      template.append("&status={status}");
      variables.add(status);
    }
    return backendApiClient.get(template.toString(), PARTNER_PAGE, variables.toArray());
  }

  /**
   * Creates a partner.
   *
   * @param request the partner
   * @return the created partner
   */
  @Nullable
  public PartnerDto createPartner(@NotNull CreatePartnerRequest request) {
    return backendApiClient.post(PARTNERS, request, PartnerDto.class);
  }

  /**
   * Edits a partner.
   *
   * @param id the partner
   * @param request the new values and the echoed version
   * @return the edited partner
   */
  @Nullable
  public PartnerDto updatePartner(@NotNull UUID id, @NotNull UpdatePartnerRequest request) {
    return backendApiClient.put(PARTNERS + "/{id}", request, PartnerDto.class, id);
  }

  /**
   * Changes the status of a partner: {@code block}, {@code unblock} or {@code erase}.
   *
   * @param id the partner
   * @param action the change
   * @param request the echoed version
   * @return the changed partner
   */
  @Nullable
  public PartnerDto changePartner(
      @NotNull UUID id, @NotNull PartnerAction action, @NotNull PurchasingVersionRequest request) {
    String template =
        switch (action) {
          case BLOCK -> PARTNERS + "/{id}/block";
          case UNBLOCK -> PARTNERS + "/{id}/unblock";
          case ERASE -> PARTNERS + "/{id}/erase";
        };
    return backendApiClient.post(template, request, PartnerDto.class, id);
  }

  /**
   * Reads the delegates, newest first.
   *
   * @return the appointments, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<DelegateDto> delegates() {
    return backendApiClient.get(DELEGATES, DELEGATE_LIST);
  }

  /**
   * Appoints a delegate.
   *
   * @param request the member, the end date and the personal limit
   * @return the appointment
   */
  @Nullable
  public DelegateDto appoint(@NotNull AppointDelegateRequest request) {
    return backendApiClient.post(DELEGATES, request, DelegateDto.class);
  }

  /**
   * Revokes an appointment.
   *
   * @param id the appointment
   * @param request the echoed version
   * @return the revoked appointment
   */
  @Nullable
  public DelegateDto revoke(@NotNull UUID id, @NotNull PurchasingVersionRequest request) {
    return backendApiClient.post(DELEGATES + "/{id}/revoke", request, DelegateDto.class, id);
  }

  /**
   * Sets the personal limit of a delegate.
   *
   * @param id the appointment
   * @param request the limit and the echoed version
   * @return the appointment
   */
  @Nullable
  public DelegateDto setLimit(@NotNull UUID id, @NotNull UpdateDelegateLimitRequest request) {
    return backendApiClient.put(DELEGATES + "/{id}/limit", request, DelegateDto.class, id);
  }

  /**
   * Reads where purchasing is paused.
   *
   * @return the global pause and the paused units, or {@code null} when the backend sent no body
   */
  @Nullable
  public PauseStateDto pauseState() {
    return backendApiClient.get(PAUSE, PauseStateDto.class);
  }

  /**
   * Pauses purchasing.
   *
   * @param request the scope and the reason
   * @return the pauses afterwards
   */
  @Nullable
  public PauseStateDto pause(@NotNull PausePurchasingRequest request) {
    return backendApiClient.post(PAUSE, request, PauseStateDto.class);
  }

  /**
   * Lifts a pause of purchasing.
   *
   * @param request the scope to resume
   * @return the pauses afterwards
   */
  @Nullable
  public PauseStateDto resume(@NotNull ResumePurchasingRequest request) {
    return backendApiClient.post(PAUSE + "/resume", request, PauseStateDto.class);
  }

  /**
   * Reads the purchasing report of a period.
   *
   * @param from the first month as {@code yyyy-MM}, or {@code null}
   * @param to the last month as {@code yyyy-MM}, or {@code null}
   * @return the report, or {@code null} when the backend sent no body
   */
  @Nullable
  public PurchasingReportDto report(@Nullable String from, @Nullable String to) {
    return backendApiClient.get(
        REPORT + period(from, to), PurchasingReportDto.class, periodVariables(from, to));
  }

  /**
   * Downloads the report of a period as CSV.
   *
   * @param from the first month as {@code yyyy-MM}, or {@code null}
   * @param to the last month as {@code yyyy-MM}, or {@code null}
   * @return the CSV bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] reportCsv(@Nullable String from, @Nullable String to) {
    String template = REPORT + "/export" + period(from, to);
    Object[] variables = periodVariables(from, to);
    return backendApiClient.execute(
        HttpMethod.GET,
        template,
        webClient -> webClient.get().uri(template, variables),
        spec -> spec.bodyToMono(byte[].class));
  }

  /**
   * Reads the forum-safe text block of the report of a period.
   *
   * @param from the first month as {@code yyyy-MM}, or {@code null}
   * @param to the last month as {@code yyyy-MM}, or {@code null}
   * @return the text, or {@code null} when the backend sent no body
   */
  @Nullable
  public String reportForumText(@Nullable String from, @Nullable String to) {
    return backendApiClient.get(
        REPORT + "/forum-text" + period(from, to), String.class, periodVariables(from, to));
  }

  /**
   * Reads the switch and the settings.
   *
   * @return the settings, or {@code null} when the backend sent no body
   */
  @Nullable
  public PurchasingSettingsDto settings() {
    return backendApiClient.get(ADMIN + "/settings", PurchasingSettingsDto.class);
  }

  /**
   * Stores the switch and the settings.
   *
   * @param request the new values and the echoed version
   * @return the stored settings
   */
  @Nullable
  public PurchasingSettingsDto updateSettings(@NotNull UpdatePurchasingSettingsRequest request) {
    return backendApiClient.put(ADMIN + "/settings", request, PurchasingSettingsDto.class);
  }

  /**
   * Reads the risk-class table.
   *
   * @return the three classes, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<RiskClassDto> riskClasses() {
    return backendApiClient.get(ADMIN + "/risk-classes", RISK_CLASS_LIST);
  }

  /**
   * Stores changes of the risk-class table.
   *
   * @param request the classes to change
   * @return the whole table afterwards
   */
  @Nullable
  public List<RiskClassDto> updateRiskClasses(@NotNull UpdateRiskClassesRequest request) {
    return backendApiClient.execute(
        HttpMethod.PUT,
        ADMIN + "/risk-classes",
        webClient -> webClient.put().uri(ADMIN + "/risk-classes").bodyValue(request),
        spec -> spec.bodyToMono(RISK_CLASS_LIST));
  }

  /**
   * Reads the bank accounts that can be chosen as the funding account.
   *
   * @return the candidates, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<FundingAccountOptionDto> fundingAccounts() {
    return backendApiClient.get(ADMIN + "/funding-accounts", ACCOUNT_LIST);
  }

  /**
   * Reads the cached list of the active org units of every kind, for the governing Bereich.
   *
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> activeOrgUnits() {
    return backendApiClient.getCached(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS, UNIT_LIST);
  }

  /**
   * Reads the cached location lookup catalogue, for the delivery dialogs.
   *
   * @return the locations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<LocationReferenceDto> locations() {
    return backendApiClient.getCached(CachedCatalog.LOCATIONS_LOOKUP, LOCATION_LIST);
  }

  private static @NotNull String period(@Nullable String from, @Nullable String to) {
    StringBuilder out = new StringBuilder();
    String separator = "?";
    if (from != null && !from.isBlank()) {
      out.append(separator).append("from={from}");
      separator = "&";
    }
    if (to != null && !to.isBlank()) {
      out.append(separator).append("to={to}");
    }
    return out.toString();
  }

  private static Object @NotNull [] periodVariables(@Nullable String from, @Nullable String to) {
    List<Object> variables = new ArrayList<>();
    if (from != null && !from.isBlank()) {
      variables.add(from);
    }
    if (to != null && !to.isBlank()) {
      variables.add(to);
    }
    return variables.toArray();
  }

  /** The status changes of a partner that carry only the echoed version. */
  public enum PartnerAction {

    /** Blocks the partner. */
    BLOCK,

    /** Lifts the block. */
    UNBLOCK,

    /** Erases the handle and the notes of an individual. */
    ERASE
  }
}
