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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient.PartnerAction;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AcceptDeliveryRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AppointDelegateRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.CreateDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.CreatePartnerRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DelegateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PausePurchasingRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PauseStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingSettingsDto;
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
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * AJAX proxy for every purchasing mutation ({@code /api/proxy/purchasing/**}), forwarding the JSON
 * body to the matching {@code /api/v1/purchasing/**} endpoint, and for the report downloads.
 * Backend RFC 7807 errors reach the browser as localized JSON; all authorization is decided by the
 * backend.
 */
@RestController
@RequestMapping("/api/proxy/purchasing")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PurchasingProxyController {

  private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

  /** The purchasing domain's backend calls. */
  private final PurchasingBackendClient purchasingClient;

  /**
   * Forwards a new draft deal.
   *
   * @param request the deal
   * @return the created draft
   */
  @PostMapping("/deals")
  @ResponseStatus(HttpStatus.CREATED)
  public DealDto createDeal(@RequestBody @Valid @NotNull CreateDealRequest request) {
    return purchasingClient.createDeal(request);
  }

  /**
   * Forwards a draft edit.
   *
   * @param id the deal
   * @param request the new values and the echoed version
   * @return the edited draft
   */
  @PutMapping("/deals/{id}")
  public DealDto updateDeal(
      @PathVariable UUID id, @RequestBody @Valid @NotNull UpdateDealRequest request) {
    return purchasingClient.updateDeal(id, request);
  }

  /**
   * Forwards the cancellation of a deal.
   *
   * @param id the deal
   * @param request the echoed version
   * @return the cancelled deal
   */
  @PostMapping("/deals/{id}/cancel")
  public DealDto cancelDeal(
      @PathVariable UUID id, @RequestBody @Valid @NotNull PurchasingVersionRequest request) {
    return purchasingClient.cancelDeal(id, request);
  }

  /**
   * Forwards a tranche request.
   *
   * @param id the deal
   * @param request kind and amount
   * @return the deal with the new tranche
   */
  @PostMapping("/deals/{id}/tranches")
  @ResponseStatus(HttpStatus.CREATED)
  public DealDto requestTranche(
      @PathVariable UUID id, @RequestBody @Valid @NotNull RequestTrancheRequest request) {
    return purchasingClient.requestTranche(id, request);
  }

  /**
   * Forwards a delivery record.
   *
   * @param id the deal
   * @param request the delivered amount and location
   * @return the deal with the new delivery
   */
  @PostMapping("/deals/{id}/deliveries")
  @ResponseStatus(HttpStatus.CREATED)
  public DealDto recordDelivery(
      @PathVariable UUID id, @RequestBody @Valid @NotNull RecordDeliveryRequest request) {
    return purchasingClient.recordDelivery(id, request);
  }

  /**
   * Forwards the decision on a delivery.
   *
   * @param id the deal
   * @param deliveryId the delivery
   * @param request accepted and rejected amounts, quality, location and the echoed version
   * @return the deal after the decision
   */
  @PostMapping("/deals/{id}/deliveries/{deliveryId}/accept")
  public DealDto acceptDelivery(
      @PathVariable UUID id,
      @PathVariable UUID deliveryId,
      @RequestBody @Valid @NotNull AcceptDeliveryRequest request) {
    return purchasingClient.acceptDelivery(id, deliveryId, request);
  }

  /**
   * Forwards the close of a delivery.
   *
   * @param id the deal
   * @return the closed deal
   */
  @PostMapping("/deals/{id}/close-delivery")
  public DealDto closeDelivery(@PathVariable UUID id) {
    return purchasingClient.closeDelivery(id);
  }

  /**
   * Forwards the settlement of a deal.
   *
   * @param id the deal
   * @param request money paid, loss and the echoed settlement version
   * @return the deal with its settlement
   */
  @PutMapping("/deals/{id}/settlement")
  public DealDto settle(
      @PathVariable UUID id, @RequestBody @Valid @NotNull SettleDealRequest request) {
    return purchasingClient.settle(id, request);
  }

  /**
   * Forwards a new partner.
   *
   * @param request the partner
   * @return the created partner
   */
  @PostMapping("/partners")
  @ResponseStatus(HttpStatus.CREATED)
  public PartnerDto createPartner(@RequestBody @Valid @NotNull CreatePartnerRequest request) {
    return purchasingClient.createPartner(request);
  }

  /**
   * Forwards a partner edit.
   *
   * @param id the partner
   * @param request the new values and the echoed version
   * @return the edited partner
   */
  @PutMapping("/partners/{id}")
  public PartnerDto updatePartner(
      @PathVariable UUID id, @RequestBody @Valid @NotNull UpdatePartnerRequest request) {
    return purchasingClient.updatePartner(id, request);
  }

  /**
   * Forwards the block of a partner.
   *
   * @param id the partner
   * @param request the echoed version
   * @return the blocked partner
   */
  @PostMapping("/partners/{id}/block")
  public PartnerDto blockPartner(
      @PathVariable UUID id, @RequestBody @Valid @NotNull PurchasingVersionRequest request) {
    return purchasingClient.changePartner(id, PartnerAction.BLOCK, request);
  }

  /**
   * Forwards the unblock of a partner.
   *
   * @param id the partner
   * @param request the echoed version
   * @return the unblocked partner
   */
  @PostMapping("/partners/{id}/unblock")
  public PartnerDto unblockPartner(
      @PathVariable UUID id, @RequestBody @Valid @NotNull PurchasingVersionRequest request) {
    return purchasingClient.changePartner(id, PartnerAction.UNBLOCK, request);
  }

  /**
   * Forwards the erasure of an individual partner.
   *
   * @param id the partner
   * @param request the echoed version
   * @return the erased partner
   */
  @PostMapping("/partners/{id}/erase")
  public PartnerDto erasePartner(
      @PathVariable UUID id, @RequestBody @Valid @NotNull PurchasingVersionRequest request) {
    return purchasingClient.changePartner(id, PartnerAction.ERASE, request);
  }

  /**
   * Forwards an appointment.
   *
   * @param request the member, the end date and the personal limit
   * @return the appointment
   */
  @PostMapping("/delegates")
  @ResponseStatus(HttpStatus.CREATED)
  public DelegateDto appoint(@RequestBody @Valid @NotNull AppointDelegateRequest request) {
    return purchasingClient.appoint(request);
  }

  /**
   * Forwards the revocation of an appointment.
   *
   * @param id the appointment
   * @param request the echoed version
   * @return the revoked appointment
   */
  @PostMapping("/delegates/{id}/revoke")
  public DelegateDto revoke(
      @PathVariable UUID id, @RequestBody @Valid @NotNull PurchasingVersionRequest request) {
    return purchasingClient.revoke(id, request);
  }

  /**
   * Forwards the limit of a delegate.
   *
   * @param id the appointment
   * @param request the limit and the echoed version
   * @return the appointment
   */
  @PutMapping("/delegates/{id}/limit")
  public DelegateDto setLimit(
      @PathVariable UUID id, @RequestBody @Valid @NotNull UpdateDelegateLimitRequest request) {
    return purchasingClient.setLimit(id, request);
  }

  /**
   * Forwards a pause.
   *
   * @param request the scope and the reason
   * @return the pauses afterwards
   */
  @PostMapping("/pause")
  public PauseStateDto pause(@RequestBody @Valid @NotNull PausePurchasingRequest request) {
    return purchasingClient.pause(request);
  }

  /**
   * Forwards the lifting of a pause.
   *
   * @param request the scope to resume
   * @return the pauses afterwards
   */
  @PostMapping("/pause/resume")
  public PauseStateDto resume(@RequestBody @Valid @NotNull ResumePurchasingRequest request) {
    return purchasingClient.resume(request);
  }

  /**
   * Downloads the report of a period as CSV.
   *
   * @param from the first month as {@code yyyy-MM}
   * @param to the last month as {@code yyyy-MM}
   * @return the CSV file
   */
  @GetMapping("/report/export")
  public ResponseEntity<byte[]> exportReport(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    requireMonth(from);
    requireMonth(to);
    byte[] body = withBackendStatus(() -> purchasingClient.reportCsv(from, to));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("purchasing-report.csv").build().toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(body == null ? new byte[0] : body);
  }

  /**
   * Reads the forum-safe text block of the report of a period.
   *
   * @param from the first month as {@code yyyy-MM}
   * @param to the last month as {@code yyyy-MM}
   * @return the text
   */
  @GetMapping("/report/forum-text")
  public ResponseEntity<String> forumText(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    requireMonth(from);
    requireMonth(to);
    String body = withBackendStatus(() -> purchasingClient.reportForumText(from, to));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
        .body(body == null ? "" : body);
  }

  /**
   * Forwards the switch and the settings.
   *
   * @param request the new values and the echoed version
   * @return the stored settings
   */
  @PutMapping("/admin/settings")
  @PreAuthorize("hasRole('ADMIN')")
  public PurchasingSettingsDto updateSettings(
      @RequestBody @Valid @NotNull UpdatePurchasingSettingsRequest request) {
    return purchasingClient.updateSettings(request);
  }

  /**
   * Forwards changes of the risk-class table.
   *
   * @param request the classes to change
   * @return the whole table afterwards
   */
  @PutMapping("/admin/risk-classes")
  @PreAuthorize("hasRole('ADMIN')")
  public List<RiskClassDto> updateRiskClasses(
      @RequestBody @Valid @NotNull UpdateRiskClassesRequest request) {
    return purchasingClient.updateRiskClasses(request);
  }

  private static void requireMonth(@Nullable String value) {
    if (value != null && !value.isBlank() && !MONTH.matcher(value).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid month");
    }
  }
}
