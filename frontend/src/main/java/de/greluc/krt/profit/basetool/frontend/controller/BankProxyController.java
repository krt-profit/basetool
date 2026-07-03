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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin AJAX proxy for every bank mutation ({@code /api/proxy/bank/**}, epic #556). Browser-side JS
 * posts here with the CSRF header; the proxy forwards the raw JSON body to the corresponding {@code
 * /api/v1/bank/**} backend endpoint with the OAuth2 bearer attached by {@link BackendApiClient}.
 * Backend errors (RFC 7807, incl. the stable bank 409 codes like {@code BANK_OVERDRAFT}) propagate
 * as {@code BackendServiceException} and reach the browser as the localized JSON body built by the
 * frontend {@code GlobalExceptionHandler} — bank.js renders them as inline field errors (K1 mockup:
 * 409 never toast-only).
 *
 * <p>Authentication is enforced at this seam; every real authorization decision (roles, capability
 * flags) lives in the backend gates.
 */
@RestController
@RequestMapping("/api/proxy/bank")
@RequiredArgsConstructor
@Slf4j
public class BankProxyController {

  private final BackendApiClient backendApiClient;

  /**
   * Forwards a deposit booking.
   *
   * @param body the raw booking payload
   * @return the backend acknowledgement
   */
  @PostMapping("/deposits")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> bookDeposit(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/deposits", body);
  }

  /**
   * Forwards a withdrawal booking.
   *
   * @param body the raw booking payload
   * @return the backend acknowledgement
   */
  @PostMapping("/withdrawals")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> bookWithdrawal(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/withdrawals", body);
  }

  /**
   * Forwards an account-to-account transfer.
   *
   * @param body the raw booking payload
   * @return the backend acknowledgement
   */
  @PostMapping("/transfers")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> bookTransfer(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/transfers", body);
  }

  /**
   * Forwards a holder→holder Umbuchung (REQ-BANK-031): moves custody between two holders, touching
   * no account. Open to bank employees on the backend.
   *
   * @param body the raw Umbuchung payload (source/destination holder, amount, note)
   * @return the backend acknowledgement
   */
  @PostMapping("/holders/transfer")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> bookHolderTransfer(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/holders/transfer", body);
  }

  /**
   * Forwards a reversal of one transaction (management-only on the backend).
   *
   * @param id the transaction to reverse
   * @param body the optional correction note payload
   * @return the backend acknowledgement
   */
  @PostMapping("/transactions/{id}/reversal")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> reverseTransaction(
      @PathVariable @NotNull UUID id, @RequestBody(required = false) Map<String, Object> body) {
    return postMap("/api/v1/bank/transactions/" + id + "/reversal", body == null ? Map.of() : body);
  }

  /**
   * Forwards a bank employee's confirmation of a pending booking request (epic #666 F2): records
   * the holder and books it onto the ledger (REQ-BANK-023). Capability/visibility 409s and
   * overdraft conflicts surface inline.
   *
   * @param id the request to confirm
   * @param body the confirm payload (holderId + echoed version)
   * @return the confirmed request
   */
  @PostMapping("/requests/{id}/confirm")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> confirmBookingRequest(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/requests/" + id + "/confirm", body);
  }

  /**
   * Forwards a bank employee's rejection of a pending booking request (epic #666 F2, REQ-BANK-023):
   * records a reason and books nothing.
   *
   * @param id the request to reject
   * @param body the reject payload (reason + echoed version)
   * @return the rejected request
   */
  @PostMapping("/requests/{id}/reject")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> rejectBookingRequest(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/requests/" + id + "/reject", body);
  }

  /**
   * Forwards an account creation (the backend lets management create any type and employees create
   * SPECIAL only, REQ-BANK-030; singleton 409s surface inline).
   *
   * @param body the raw creation payload
   * @return the created account
   */
  @PostMapping("/accounts")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createAccount(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/accounts", body);
  }

  /**
   * Forwards an account rename.
   *
   * @param id the account
   * @param body the rename payload (name + echoed version)
   * @return the updated account
   */
  @PatchMapping("/accounts/{id}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> renameAccount(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/accounts/" + id, body);
  }

  /**
   * Forwards setting/clearing an account's balance target (REQ-BANK-036). A {@code null} target in
   * the body clears it.
   *
   * @param id the account
   * @param body the target payload (target + echoed version)
   * @return the updated account
   */
  @PatchMapping("/accounts/{id}/balance-target")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setBalanceTarget(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/accounts/" + id + "/balance-target", body);
  }

  /**
   * Forwards setting/clearing the KRT-account (CARTEL) 3-stage approval thresholds T1/T2
   * (REQ-BANK-047). Bank-management-only, enforced by the backend. A {@code null} ceiling in the
   * body clears that band.
   *
   * @param id the KRT account
   * @param body the thresholds payload (employeeCeiling + areaLeadCeiling + echoed version)
   * @return the updated account
   */
  @PatchMapping("/accounts/{id}/approval-tiers")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setCartelApprovalTiers(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/accounts/" + id + "/approval-tiers", body);
  }

  /**
   * Forwards an account close (zero-balance rule enforced by the backend, 409 inline).
   *
   * @param id the account
   * @param body the lifecycle payload (echoed version)
   * @return the updated account
   */
  @PostMapping("/accounts/{id}/close")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> closeAccount(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/accounts/" + id + "/close", body);
  }

  /**
   * Forwards an account reopen.
   *
   * @param id the account
   * @param body the lifecycle payload (echoed version)
   * @return the updated account
   */
  @PostMapping("/accounts/{id}/reopen")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> reopenAccount(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/accounts/" + id + "/reopen", body);
  }

  /**
   * Forwards a holder registration.
   *
   * @param body the registration payload (userId)
   * @return the created holder row
   */
  @PostMapping("/holders")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> registerHolder(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/holders", body);
  }

  /**
   * Forwards a holder activity toggle.
   *
   * @param id the holder row
   * @param body the toggle payload (active + echoed version)
   * @return the updated holder row
   */
  @PatchMapping("/holders/{id}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> updateHolder(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/holders/" + id, body);
  }

  /**
   * Forwards a grant creation (grantee-role rule enforced by the backend, 409 inline).
   *
   * @param body the creation payload
   * @return the created grant row
   */
  @PostMapping("/grants")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createGrant(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/bank/grants", body);
  }

  /**
   * Forwards a grant flag change (the matrix toggles PATCH directly, W1/G1 mockup).
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   * @param body the flag payload (three flags + echoed version)
   * @return the updated grant row
   */
  @PatchMapping("/grants/{userId}/{accountId}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> updateGrant(
      @PathVariable @NotNull UUID userId,
      @PathVariable @NotNull UUID accountId,
      @RequestBody @NotNull Map<String, Object> body) {
    return patchMap("/api/v1/bank/grants/" + userId + "/" + accountId, body);
  }

  /**
   * Forwards a grant revocation.
   *
   * @param userId the grantee half of the composite key
   * @param accountId the account half of the composite key
   */
  @DeleteMapping("/grants/{userId}/{accountId}")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGrant(
      @PathVariable @NotNull UUID userId, @PathVariable @NotNull UUID accountId) {
    backendApiClient.delete("/api/v1/bank/grants/" + userId + "/" + accountId, Void.class);
  }

  /**
   * POST helper returning the backend's JSON body as a raw map (or an empty map for bodyless 2xx
   * responses, keeping the browser contract uniform).
   *
   * @param uri the backend endpoint
   * @param body the forwarded payload
   * @return the backend response body
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> postMap(@NotNull String uri, @NotNull Map<String, Object> body) {
    Map<String, Object> response = backendApiClient.post(uri, body, Map.class);
    return response == null ? Map.of() : response;
  }

  /**
   * PATCH helper returning the backend's JSON body as a raw map.
   *
   * @param uri the backend endpoint
   * @param body the forwarded payload
   * @return the backend response body
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> patchMap(@NotNull String uri, @NotNull Map<String, Object> body) {
    Map<String, Object> response = backendApiClient.patch(uri, body, Map.class);
    return response == null ? Map.of() : response;
  }
}
