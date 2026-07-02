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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountRefDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountDetailDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CancelBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.OrgUnitBalanceTargetRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.SetBankApprovalLimitRequest;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankAccessService;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import de.greluc.krt.profit.basetool.backend.web.PdfResponses;
import de.greluc.krt.profit.basetool.backend.web.UserZone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The org-unit-facing slice of the bank, living at {@code /api/v1/org-units/bank} — deliberately
 * <em>outside</em> the {@code /api/v1/bank/**} space that URL-gates {@code BANK_EMPLOYEE}. It
 * serves the officers and leads who oversee an org unit, never the bank staff: an officer or lead
 * may read the balance of their own org unit's account (F1, REQ-BANK-021) and, in later phases,
 * raise confirm-before-post booking requests against it (F2, REQ-BANK-022). All org-unit logic
 * lives in {@link OrgUnitBankAccessService}; this controller only relays. Authorization is
 * coarse-gated to any authenticated caller and finely scoped in the service via the oversight
 * scope, so a caller who oversees nothing receives an empty result rather than another caller's
 * data.
 */
@RestController
@RequestMapping("/api/v1/org-units/bank")
@RequiredArgsConstructor
@Tag(
    name = "org-unit-bank-controller",
    description = "Org-unit officer/lead bank access (balance view, booking requests)")
public class OrgUnitBankController {

  private static final Set<String> BOOKING_SORT_FIELDS = Set.of("createdAt", "id");

  private final OrgUnitBankAccessService orgUnitBankAccessService;

  /**
   * Returns the balance-only view of every org-unit account the caller oversees (REQ-BANK-021, F1).
   * {@code isAuthenticated}: the fine-grained scope is decided in the service from the caller's
   * oversight scope (officer → own Staffel, SK lead → led SK(s), admin → all/pinned), so a plain
   * member gets an empty list and the endpoint never reveals accounts outside the caller's scope.
   *
   * @return the overseen org-unit balances, ordered by account number; empty when the caller
   *     oversees no org unit that owns an account
   */
  @GetMapping("/balances")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "List the balances of the org-unit accounts the caller oversees",
      description =
          "Returns the current balance of each org-unit bank account the authenticated officer or"
              + " lead oversees (their own Staffel / the Spezialkommando(s) they lead; admins see"
              + " all or the pinned org unit). Balance-only by design — no history, no holders, no"
              + " audit. A caller with no oversight scope receives an empty list.")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Overseen org-unit balances")})
  public List<OrgUnitBankBalanceDto> listOverseenBalances() {
    return orgUnitBankAccessService.listOverseenOrgUnitBalances();
  }

  /**
   * Returns the read-only account detail an org-unit viewer sees when they open an account from the
   * card list (REQ-BANK-038): the same shape as the bank-staff detail but with all-false
   * capabilities, plus the org-unit affordances (export statement, manage settings, request). The
   * seam authorizes that the caller may view the account.
   *
   * @param id the account
   * @return the read-only detail
   */
  @GetMapping("/accounts/{id}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Read-only detail of an org-unit account the caller may view")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Read-only account detail")})
  public OrgUnitBankAccountDetailDto getAccountDetail(@PathVariable @NotNull UUID id) {
    return orgUnitBankAccessService.getViewableAccountDetail(id);
  }

  /**
   * Pages over an account's booking history for an org-unit viewer (REQ-BANK-038), with the
   * player-custody ("Halter") columns redacted by the seam. Newest first by default.
   *
   * @param id the account
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of redacted booking rows
   */
  @GetMapping("/accounts/{id}/transactions")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Booking history of an org-unit account (Halter redacted)")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Redacted booking history")})
  public PageResponse<BankBookingDto> getAccountBookings(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    String effectiveSort = sort == null || sort.isBlank() ? "createdAt,desc" : sort;
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, effectiveSort, BOOKING_SORT_FIELDS, "createdAt");
    return PageResponse.of(orgUnitBankAccessService.getViewableAccountBookings(id, pageable));
  }

  /**
   * Downloads the Halter-redacted account statement PDF for an org-unit viewer (REQ-BANK-038). The
   * seam authorizes view access and records the export; the optional {@code X-User-Time-Zone}
   * header overrides UTC for the document timestamps.
   *
   * @param id the account
   * @param from period start (inclusive, ISO-8601 instant)
   * @param to period end (inclusive, ISO-8601 instant)
   * @param userZone the resolved {@code X-User-Time-Zone} zone, or {@code null} for UTC
   * @return PDF body with {@code application/pdf} and attachment Content-Disposition
   */
  @GetMapping("/accounts/{id}/statement")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Download the Halter-redacted account statement PDF (org-unit viewer)")
  @Parameter(
      name = "X-User-Time-Zone",
      in = ParameterIn.HEADER,
      required = false,
      schema = @Schema(type = "string"))
  public ResponseEntity<byte[]> downloadStatement(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull Instant from,
      @RequestParam @NotNull Instant to,
      @UserZone ZoneId userZone) {
    byte[] pdf = orgUnitBankAccessService.exportViewableStatement(id, from, to, userZone);
    return PdfResponses.pdfAttachment(pdf, "kontoauszug-" + id + ".pdf");
  }

  /**
   * Returns the responsibility settings of one account (REQ-BANK-035/-036) — the current balance
   * target and visibility grants, plus which controls the caller may use. The seam authorizes that
   * the caller may manage the account.
   *
   * @param id the account
   * @return the settings snapshot
   */
  @GetMapping("/accounts/{id}/settings")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Read an org-unit account's responsibility settings (holder/OL)")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Account settings")})
  public OrgUnitBankAccountSettingsDto getAccountSettings(@PathVariable @NotNull UUID id) {
    return orgUnitBankAccessService.getAccountSettings(id);
  }

  /**
   * Sets or clears an account's balance target (REQ-BANK-036). A {@code null} target clears it. The
   * seam authorizes that the caller is the responsible holder.
   *
   * @param id the account
   * @param request the new target (or {@code null} to clear) plus the echoed version
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/balance-target")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Set or clear an org-unit account's balance target (responsible holder)")
  public OrgUnitBankAccountSettingsDto setBalanceTarget(
      @PathVariable @NotNull UUID id, @RequestBody @Valid OrgUnitBalanceTargetRequest request) {
    return orgUnitBankAccessService.setBalanceTarget(id, request.target(), request.version());
  }

  /**
   * Grants a role bucket view access to an account (REQ-BANK-035). The seam derives the kind from
   * the account type and validates the role code.
   *
   * @param id the account
   * @param roleCode the role bucket to grant
   * @return the refreshed settings
   */
  @PostMapping("/accounts/{id}/visibility/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Grant a role bucket view access to an org-unit account")
  public OrgUnitBankAccountSettingsDto addRoleVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull String roleCode) {
    return orgUnitBankAccessService.addRoleVisibility(id, roleCode);
  }

  /**
   * Revokes a role bucket's view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param roleCode the role bucket to revoke
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/visibility/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Revoke a role bucket's view access to an org-unit account")
  public OrgUnitBankAccountSettingsDto removeRoleVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull String roleCode) {
    return orgUnitBankAccessService.removeRoleVisibility(id, roleCode);
  }

  /**
   * Enables or disables the all-members view grant of an account (REQ-BANK-035).
   *
   * @param id the account
   * @param enabled whether all members may view the account
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/visibility/all-members/{enabled}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Toggle the all-members view grant of an org-unit account")
  public OrgUnitBankAccountSettingsDto setAllMembersVisibility(
      @PathVariable @NotNull UUID id, @PathVariable boolean enabled) {
    return orgUnitBankAccessService.setAllMembersVisibility(id, enabled);
  }

  /**
   * Enables or disables the "Mitglieder des Bereichs" cascade view grant of a Bereichskonto
   * (REQ-BANK-048): every member of the whole area cascade (Bereichsleitung + child Staffel/SK
   * members) may view it.
   *
   * @param id the account
   * @param enabled whether the whole area cascade may view the account
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/visibility/area-members/{enabled}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Toggle the area-members (Mitglieder des Bereichs) view grant")
  public OrgUnitBankAccountSettingsDto setAreaMembersVisibility(
      @PathVariable @NotNull UUID id, @PathVariable boolean enabled) {
    return orgUnitBankAccessService.setAreaMembersVisibility(id, enabled);
  }

  /**
   * Grants an individual user view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to grant
   * @return the refreshed settings
   */
  @PostMapping("/accounts/{id}/visibility/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Grant an individual user view access to an org-unit account")
  public OrgUnitBankAccountSettingsDto addUserVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return orgUnitBankAccessService.addUserVisibility(id, userId);
  }

  /**
   * Revokes an individual user's view access to an account (REQ-BANK-035).
   *
   * @param id the account
   * @param userId the user to revoke
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/visibility/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Revoke an individual user's view access to an org-unit account")
  public OrgUnitBankAccountSettingsDto removeUserVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return orgUnitBankAccessService.removeUserVisibility(id, userId);
  }

  /**
   * Sets or changes a role-bucket approval limit on an account (REQ-BANK-041). The seam authorizes
   * the caller (responsible holder / bank management / admin) and validates the role bucket.
   *
   * @param id the account
   * @param roleCode the role bucket
   * @param request the new whole-aUEC limit (>= 0)
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Set a role-bucket approval limit on an org-unit account")
  public OrgUnitBankAccountSettingsDto setRoleApprovalLimit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull String roleCode,
      @RequestBody @Valid SetBankApprovalLimitRequest request) {
    return orgUnitBankAccessService.setRoleApprovalLimit(id, roleCode, request.limit());
  }

  /**
   * Clears a role-bucket approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket to clear
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Clear a role-bucket approval limit on an org-unit account")
  public OrgUnitBankAccountSettingsDto clearRoleApprovalLimit(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull String roleCode) {
    return orgUnitBankAccessService.clearRoleApprovalLimit(id, roleCode);
  }

  /**
   * Sets or changes the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param request the new whole-aUEC limit (>= 0)
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/all-members")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Set the all-members approval limit on an org-unit account")
  public OrgUnitBankAccountSettingsDto setAllMembersApprovalLimit(
      @PathVariable @NotNull UUID id, @RequestBody @Valid SetBankApprovalLimitRequest request) {
    return orgUnitBankAccessService.setAllMembersApprovalLimit(id, request.limit());
  }

  /**
   * Clears the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/all-members")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Clear the all-members approval limit on an org-unit account")
  public OrgUnitBankAccountSettingsDto clearAllMembersApprovalLimit(
      @PathVariable @NotNull UUID id) {
    return orgUnitBankAccessService.clearAllMembersApprovalLimit(id);
  }

  /**
   * Sets or changes the "Mitglieder des Bereichs" cascade approval limit on a Bereichskonto
   * (REQ-BANK-048).
   *
   * @param id the account
   * @param request the new whole-aUEC limit (>= 0)
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/area-members")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Set the area-members (Mitglieder des Bereichs) approval limit")
  public OrgUnitBankAccountSettingsDto setAreaMembersApprovalLimit(
      @PathVariable @NotNull UUID id, @RequestBody @Valid SetBankApprovalLimitRequest request) {
    return orgUnitBankAccessService.setAreaMembersApprovalLimit(id, request.limit());
  }

  /**
   * Clears the "Mitglieder des Bereichs" cascade approval limit on a Bereichskonto (REQ-BANK-048).
   *
   * @param id the account
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/area-members")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Clear the area-members (Mitglieder des Bereichs) approval limit")
  public OrgUnitBankAccountSettingsDto clearAreaMembersApprovalLimit(
      @PathVariable @NotNull UUID id) {
    return orgUnitBankAccessService.clearAreaMembersApprovalLimit(id);
  }

  /**
   * Sets or changes an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user the limit addresses
   * @param request the new whole-aUEC limit (>= 0)
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Set an individual user's approval limit on an org-unit account")
  public OrgUnitBankAccountSettingsDto setUserApprovalLimit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody @Valid SetBankApprovalLimitRequest request) {
    return orgUnitBankAccessService.setUserApprovalLimit(id, userId, request.limit());
  }

  /**
   * Clears an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user whose limit to clear
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Clear an individual user's approval limit on an org-unit account")
  public OrgUnitBankAccountSettingsDto clearUserApprovalLimit(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return orgUnitBankAccessService.clearUserApprovalLimit(id, userId);
  }

  /**
   * Raises a confirm-before-post booking request (REQ-BANK-022/-039/-042, F2). The request is
   * recorded as {@code PENDING} and audited, but moves no money until a bank employee confirms it.
   * A <em>deposit</em> may target any active account (REQ-BANK-042); a <em>withdrawal /
   * transfer</em> is gated by view eligibility on the source account (REQ-BANK-039).
   *
   * @param request the create payload (source account, type, amount, optional destination, note)
   * @return the created pending request
   */
  @PostMapping("/requests")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Raise a confirm-before-post booking request",
      description =
          "Creates a PENDING booking request that is audited immediately but moves no money until a"
              + " bank employee confirms it; no holder is chosen by the requester. A deposit may be"
              + " requested by any authenticated caller against any active account and is never"
              + " approval-limited (REQ-BANK-042); a withdrawal/transfer is restricted to an"
              + " account the caller may view and subject to the per-tier approval limits.")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Request created")})
  public BankBookingRequestDto createBookingRequest(
      @Valid @RequestBody CreateBankBookingRequest request) {
    return orgUnitBankAccessService.createBookingRequest(request);
  }

  /**
   * Lists the caller's own booking requests with their current status (REQ-BANK-022). Per-user
   * isolation — never another requester's requests.
   *
   * @return the caller's requests, newest first
   */
  @GetMapping("/requests")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "List the caller's own booking requests and their status")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "The caller's requests")})
  public List<BankBookingRequestDto> listOwnBookingRequests() {
    return orgUnitBankAccessService.listOwnBookingRequests();
  }

  /**
   * Cancels one of the caller's own pending booking requests (REQ-BANK-022). A request that is not
   * the caller's, or no longer pending, is rejected.
   *
   * @param id the request to cancel
   * @param request the echoed optimistic-locking version
   * @return the cancelled request
   */
  @PostMapping("/requests/{id}/cancel")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Cancel one of the caller's own pending booking requests")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Request cancelled")})
  public BankBookingRequestDto cancelOwnBookingRequest(
      @PathVariable UUID id, @Valid @RequestBody CancelBankBookingRequest request) {
    return orgUnitBankAccessService.cancelOwnBookingRequest(id, request.version());
  }

  /**
   * Lists every booking request raised against the accounts the caller is responsible for — the
   * "Fremde Anträge" tab (REQ-BANK-041). The seam scopes the result to the caller's responsible
   * accounts (admins see all).
   *
   * @return the requests on the caller's responsible accounts, newest first
   */
  @GetMapping("/requests/foreign")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "List booking requests on the accounts the caller is responsible for")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Requests on own accounts")})
  public List<BankBookingRequestDto> listForeignRequests() {
    return orgUnitBankAccessService.listRequestsForResponsibleAccounts();
  }

  /**
   * Lists all active accounts as transfer-request destinations (REQ-BANK-040): a requester may
   * transfer from a viewable source account to any active account.
   *
   * @return the active accounts as transfer targets
   */
  @GetMapping("/transfer-targets")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "List all active accounts as transfer-request destinations")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Active accounts")})
  public List<BankAccountRefDto> listTransferTargets() {
    return orgUnitBankAccessService.listTransferTargetAccounts();
  }

  /**
   * Grants the responsible holder's in-app approval for an over-limit request (REQ-BANK-041), which
   * pre-fills the bank employee's confirmation checkbox. The seam authorizes the caller as the
   * account's responsible holder (or admin).
   *
   * @param id the request to approve
   * @return the updated request
   */
  @PostMapping("/requests/{id}/owner-approval")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Grant the responsible holder's in-app approval for a booking request")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Approval granted")})
  public BankBookingRequestDto grantOwnerApproval(@PathVariable @NotNull UUID id) {
    return orgUnitBankAccessService.grantOwnerApproval(id);
  }

  /**
   * Revokes a previously granted in-app approval for a booking request (REQ-BANK-041).
   *
   * @param id the request whose approval to revoke
   * @return the updated request
   */
  @DeleteMapping("/requests/{id}/owner-approval")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Revoke the responsible holder's in-app approval for a booking request")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Approval revoked")})
  public BankBookingRequestDto revokeOwnerApproval(@PathVariable @NotNull UUID id) {
    return orgUnitBankAccessService.revokeOwnerApproval(id);
  }
}
