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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.bank.model.CancelBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.OrgUnitBalanceTargetRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.SetBankApprovalLimitRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.UpdateBankBookingRequest;
import de.greluc.krt.profit.basetool.frontend.support.RelayParams;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * AJAX proxy for the org-unit bank actions ({@code /api/proxy/org-units/bank/**}), forwarding to
 * the matching {@code /api/v1/org-units/bank/**} backend endpoints and streaming the redacted
 * statement PDF. It requires authentication; authorization is decided by the backend.
 */
@RestController
@RequestMapping("/api/proxy/org-units/bank")
@RequiredArgsConstructor
public class OrgUnitBankProxyController {

  /** The bank domain's backend calls. */
  private final BankBackendClient bankClient;

  /**
   * Forwards a new booking request raised by an officer/lead against their overseen org unit's
   * account (REQ-BANK-022). Out-of-scope (403) and closed-account (409) surface inline.
   *
   * @param request the source account, movement kind, amount and optional fields
   * @return the created pending request
   */
  @PostMapping("/requests")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Object createRequest(@RequestBody @NotNull CreateBankBookingRequest request) {
    return orEmpty(bankClient.createOrgUnitRequest(request));
  }

  /**
   * Forwards the cancellation of the caller's own pending booking request (REQ-BANK-022).
   *
   * @param id the request to cancel
   * @param request the echoed version
   * @return the cancelled request
   */
  @PostMapping("/requests/{id}/cancel")
  @PreAuthorize("isAuthenticated()")
  public Object cancelRequest(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull CancelBankBookingRequest request) {
    return orEmpty(bankClient.cancelOrgUnitRequest(id, request));
  }

  /**
   * Forwards a correction of the caller's own still-pending, unapproved booking request
   * (REQ-BANK-056). The backend owns every guard — ownership, pending-ness, the not-yet-approved
   * precondition and the version echo — so this only relays; its errors reach the user as inline
   * field messages via {@code krtFetch}.
   *
   * @param id the request to correct
   * @param request the corrected values plus the echoed version
   * @return the updated request
   */
  @PutMapping("/requests/{id}")
  @PreAuthorize("isAuthenticated()")
  public Object updateRequest(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull UpdateBankBookingRequest request) {
    return orEmpty(bankClient.updateOrgUnitRequest(id, request));
  }

  /**
   * Forwards setting/clearing an account's balance target (REQ-BANK-036). A {@code null} target
   * clears it.
   *
   * @param id the account
   * @param request the target and the echoed version
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/balance-target")
  @PreAuthorize("isAuthenticated()")
  public Object setBalanceTarget(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull OrgUnitBalanceTargetRequest request) {
    return orEmpty(bankClient.setOrgUnitBalanceTarget(id, request));
  }

  /**
   * Forwards granting a role bucket view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param roleCode the role bucket to grant
   * @param body unused (path-only); accepted so the AJAX form may post an empty body
   * @return the refreshed settings
   */
  @PostMapping("/accounts/{id}/visibility/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  public Object addRoleVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull String roleCode,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return orEmpty(bankClient.addRoleVisibility(id, requireRoleCode(roleCode), emptyIfNull(body)));
  }

  /**
   * Forwards revoking a role bucket's view access (REQ-BANK-035).
   *
   * @param id the account
   * @param roleCode the role bucket to revoke
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/visibility/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  public Object removeRoleVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull String roleCode) {
    return orEmpty(bankClient.removeRoleVisibility(id, requireRoleCode(roleCode)));
  }

  /**
   * Forwards toggling the all-members view grant of an account (REQ-BANK-035).
   *
   * @param id the account
   * @param enabled whether all members may view the account
   * @param body unused (path-only)
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/visibility/all-members/{enabled}")
  @PreAuthorize("isAuthenticated()")
  public Object setAllMembersVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable boolean enabled,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return orEmpty(bankClient.setAllMembersVisibility(id, enabled, emptyIfNull(body)));
  }

  /**
   * Forwards toggling the "Mitglieder des Bereichs" cascade view grant of a Bereichskonto
   * (REQ-BANK-048).
   *
   * @param id the account
   * @param enabled whether the whole area cascade may view the account
   * @param body unused (path-only)
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/visibility/area-members/{enabled}")
  @PreAuthorize("isAuthenticated()")
  public Object setAreaMembersVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable boolean enabled,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return orEmpty(bankClient.setAreaMembersVisibility(id, enabled, emptyIfNull(body)));
  }

  /**
   * Forwards granting an individual user view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to grant
   * @param body unused (path-only)
   * @return the refreshed settings
   */
  @PostMapping("/accounts/{id}/visibility/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  public Object addUserVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return orEmpty(bankClient.addUserVisibility(id, userId, emptyIfNull(body)));
  }

  /**
   * Forwards revoking an individual user's view access (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to revoke
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/visibility/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  public Object removeUserVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return orEmpty(bankClient.removeUserVisibility(id, userId));
  }

  /**
   * Forwards setting a role-bucket approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket
   * @param request the limit
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  public Object setRoleApprovalLimit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull String roleCode,
      @RequestBody @NotNull SetBankApprovalLimitRequest request) {
    return orEmpty(bankClient.setRoleApprovalLimit(id, requireRoleCode(roleCode), request));
  }

  /**
   * Forwards clearing a role-bucket approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket to clear
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  public Object clearRoleApprovalLimit(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull String roleCode) {
    return orEmpty(bankClient.clearRoleApprovalLimit(id, requireRoleCode(roleCode)));
  }

  /**
   * Forwards setting the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param request the limit
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/all-members")
  @PreAuthorize("isAuthenticated()")
  public Object setAllMembersApprovalLimit(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull SetBankApprovalLimitRequest request) {
    return orEmpty(bankClient.setAllMembersApprovalLimit(id, request));
  }

  /**
   * Forwards clearing the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/all-members")
  @PreAuthorize("isAuthenticated()")
  public Object clearAllMembersApprovalLimit(@PathVariable @NotNull UUID id) {
    return orEmpty(bankClient.clearAllMembersApprovalLimit(id));
  }

  /**
   * Forwards setting the "Mitglieder des Bereichs" cascade approval limit on a Bereichskonto
   * (REQ-BANK-048).
   *
   * @param id the account
   * @param request the limit
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/area-members")
  @PreAuthorize("isAuthenticated()")
  public Object setAreaMembersApprovalLimit(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull SetBankApprovalLimitRequest request) {
    return orEmpty(bankClient.setAreaMembersApprovalLimit(id, request));
  }

  /**
   * Forwards clearing the "Mitglieder des Bereichs" cascade approval limit on a Bereichskonto
   * (REQ-BANK-048).
   *
   * @param id the account
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/area-members")
  @PreAuthorize("isAuthenticated()")
  public Object clearAreaMembersApprovalLimit(@PathVariable @NotNull UUID id) {
    return orEmpty(bankClient.clearAreaMembersApprovalLimit(id));
  }

  /**
   * Forwards setting an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user the limit addresses
   * @param request the limit
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  public Object setUserApprovalLimit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody @NotNull SetBankApprovalLimitRequest request) {
    return orEmpty(bankClient.setUserApprovalLimit(id, userId, request));
  }

  /**
   * Forwards clearing an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user whose limit to clear
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  public Object clearUserApprovalLimit(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return orEmpty(bankClient.clearUserApprovalLimit(id, userId));
  }

  /**
   * Forwards the responsible holder granting in-app approval for an over-limit request
   * (REQ-BANK-041).
   *
   * @param id the request to approve
   * @param body unused (path-only)
   * @return the updated request
   */
  @PostMapping("/requests/{id}/owner-approval")
  @PreAuthorize("isAuthenticated()")
  public Object grantOwnerApproval(
      @PathVariable @NotNull UUID id,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return orEmpty(bankClient.grantOwnerApproval(id, emptyIfNull(body)));
  }

  /**
   * Forwards the responsible holder revoking a previously granted in-app approval (REQ-BANK-041).
   *
   * @param id the request whose approval to revoke
   * @return the updated request
   */
  @DeleteMapping("/requests/{id}/owner-approval")
  @PreAuthorize("isAuthenticated()")
  public Object revokeOwnerApproval(@PathVariable @NotNull UUID id) {
    return orEmpty(bankClient.revokeOwnerApproval(id));
  }

  /**
   * Streams the Halter-redacted Kontoauszug PDF for an account the caller may view (REQ-BANK-038).
   *
   * @param id the account id
   * @param from period start; bound as an instant so the relayed value cannot carry URI syntax
   * @param to period end; bound as an instant so the relayed value cannot carry URI syntax
   * @param userTimeZone the caller's IANA time zone; optional
   * @return the PDF with attachment headers
   */
  @GetMapping("/accounts/{id}/statement")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadStatement(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestHeader(value = "X-User-Time-Zone", required = false) String userTimeZone) {
    byte[] pdf =
        withBackendStatus(() -> bankClient.orgUnitAccountStatement(id, from, to, userTimeZone));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", "kontoauszug-" + id + ".pdf");
    return ResponseEntity.ok().headers(headers).body(pdf);
  }

  /**
   * Returns the backend's answer, or an empty object for a bodyless 2xx.
   *
   * @param response the backend's answer, or {@code null}
   * @return the answer, or an empty map
   */
  @NotNull
  private static Object orEmpty(@Nullable Object response) {
    return response == null ? Map.of() : response;
  }

  /**
   * Checks that a role bucket path segment has the shape of an enum literal before it enters a
   * backend URI (REQ-SEC-051).
   *
   * @param roleCode the raw path segment
   * @return {@code roleCode} unchanged
   * @throws ResponseStatusException {@code 400} when the value is not an upper-case constant name
   */
  @NotNull
  private static String requireRoleCode(@NotNull String roleCode) {
    String checked = RelayParams.constantNameOrNull(roleCode);
    if (checked == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed role code");
    }
    return checked;
  }

  /**
   * Returns the given body or an empty map when {@code null} (path-only writes carry no body).
   *
   * @param body the optional body
   * @return the body, or an empty map
   */
  @NotNull
  private static Map<String, Object> emptyIfNull(@Nullable Map<String, Object> body) {
    return body == null ? Map.of() : body;
  }
}
