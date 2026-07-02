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
import org.jetbrains.annotations.Nullable;
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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin AJAX proxy for the org-unit officer/lead/holder bank actions ({@code
 * /api/proxy/org-units/bank/**}). Browser-side JS posts here with the CSRF header; the proxy
 * forwards the raw JSON to the corresponding {@code /api/v1/org-units/bank/**} backend endpoint
 * with the OAuth2 bearer attached by {@link BackendApiClient}, and streams the redacted Kontoauszug
 * PDF via the {@link WebClient}. Authentication is enforced at this seam; the real authorization
 * (view access, responsible-holder, OL/management for Sonderkonten) lives in the backend seam. Kept
 * separate from the bank-staff {@code BankProxyController} so the two audiences never share a path.
 */
@RestController
@RequestMapping("/api/proxy/org-units/bank")
@RequiredArgsConstructor
@Slf4j
public class OrgUnitBankProxyController {

  private final BackendApiClient backendApiClient;
  private final WebClient webClient;

  /**
   * Forwards a new booking request raised by an officer/lead against their overseen org unit's
   * account (REQ-BANK-022). Out-of-scope (403) and closed-account (409) surface inline.
   *
   * @param body the raw create payload (orgUnitId + type + amount + optional note)
   * @return the created pending request
   */
  @PostMapping("/requests")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> createRequest(@RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/org-units/bank/requests", body);
  }

  /**
   * Forwards the cancellation of the caller's own pending booking request (REQ-BANK-022).
   *
   * @param id the request to cancel
   * @param body the lifecycle payload (echoed version)
   * @return the cancelled request
   */
  @PostMapping("/requests/{id}/cancel")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> cancelRequest(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return postMap("/api/v1/org-units/bank/requests/" + id + "/cancel", body);
  }

  /**
   * Forwards setting/clearing an account's balance target (REQ-BANK-036). A {@code null} target in
   * the body clears it.
   *
   * @param id the account
   * @param body the target payload (target + version)
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/balance-target")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setBalanceTarget(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/accounts/" + id + "/balance-target", body);
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
  public Map<String, Object> addRoleVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull String roleCode,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return postMap(
        "/api/v1/org-units/bank/accounts/" + id + "/visibility/role/" + roleCode,
        emptyIfNull(body));
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
  public Map<String, Object> removeRoleVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull String roleCode) {
    return deleteMap("/api/v1/org-units/bank/accounts/" + id + "/visibility/role/" + roleCode);
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
  public Map<String, Object> setAllMembersVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable boolean enabled,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return putMap(
        "/api/v1/org-units/bank/accounts/" + id + "/visibility/all-members/" + enabled,
        emptyIfNull(body));
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
  public Map<String, Object> setAreaMembersVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable boolean enabled,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return putMap(
        "/api/v1/org-units/bank/accounts/" + id + "/visibility/area-members/" + enabled,
        emptyIfNull(body));
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
  public Map<String, Object> addUserVisibility(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return postMap(
        "/api/v1/org-units/bank/accounts/" + id + "/visibility/user/" + userId, emptyIfNull(body));
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
  public Map<String, Object> removeUserVisibility(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return deleteMap("/api/v1/org-units/bank/accounts/" + id + "/visibility/user/" + userId);
  }

  /**
   * Forwards setting a role-bucket approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param roleCode the role bucket
   * @param body the limit payload ({@code limit})
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/role/{roleCode}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setRoleApprovalLimit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull String roleCode,
      @RequestBody @NotNull Map<String, Object> body) {
    return putMap(
        "/api/v1/org-units/bank/accounts/" + id + "/approval-limit/role/" + roleCode, body);
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
  public Map<String, Object> clearRoleApprovalLimit(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull String roleCode) {
    return deleteMap("/api/v1/org-units/bank/accounts/" + id + "/approval-limit/role/" + roleCode);
  }

  /**
   * Forwards setting the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param body the limit payload ({@code limit})
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/all-members")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setAllMembersApprovalLimit(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/accounts/" + id + "/approval-limit/all-members", body);
  }

  /**
   * Forwards clearing the all-members approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @return the refreshed settings
   */
  @DeleteMapping("/accounts/{id}/approval-limit/all-members")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> clearAllMembersApprovalLimit(@PathVariable @NotNull UUID id) {
    return deleteMap("/api/v1/org-units/bank/accounts/" + id + "/approval-limit/all-members");
  }

  /**
   * Forwards setting the "Mitglieder des Bereichs" cascade approval limit on a Bereichskonto
   * (REQ-BANK-048).
   *
   * @param id the account
   * @param body the limit payload ({@code limit})
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/area-members")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setAreaMembersApprovalLimit(
      @PathVariable @NotNull UUID id, @RequestBody @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/accounts/" + id + "/approval-limit/area-members", body);
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
  public Map<String, Object> clearAreaMembersApprovalLimit(@PathVariable @NotNull UUID id) {
    return deleteMap("/api/v1/org-units/bank/accounts/" + id + "/approval-limit/area-members");
  }

  /**
   * Forwards setting an individual user's approval limit on an account (REQ-BANK-041).
   *
   * @param id the account
   * @param userId the user the limit addresses
   * @param body the limit payload ({@code limit})
   * @return the refreshed settings
   */
  @PutMapping("/accounts/{id}/approval-limit/user/{userId}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> setUserApprovalLimit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody @NotNull Map<String, Object> body) {
    return putMap("/api/v1/org-units/bank/accounts/" + id + "/approval-limit/user/" + userId, body);
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
  public Map<String, Object> clearUserApprovalLimit(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return deleteMap("/api/v1/org-units/bank/accounts/" + id + "/approval-limit/user/" + userId);
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
  public Map<String, Object> grantOwnerApproval(
      @PathVariable @NotNull UUID id,
      @RequestBody(required = false) @Nullable Map<String, Object> body) {
    return postMap("/api/v1/org-units/bank/requests/" + id + "/owner-approval", emptyIfNull(body));
  }

  /**
   * Forwards the responsible holder revoking a previously granted in-app approval (REQ-BANK-041).
   *
   * @param id the request whose approval to revoke
   * @return the updated request
   */
  @DeleteMapping("/requests/{id}/owner-approval")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> revokeOwnerApproval(@PathVariable @NotNull UUID id) {
    return deleteMap("/api/v1/org-units/bank/requests/" + id + "/owner-approval");
  }

  /**
   * Streams the Halter-redacted Kontoauszug PDF for an account the caller may view (REQ-BANK-038).
   *
   * @param id the account id
   * @param from period start (ISO-8601 instant, forwarded verbatim)
   * @param to period end (ISO-8601 instant, forwarded verbatim)
   * @param userTimeZone the caller's IANA time zone; optional
   * @return the PDF with attachment headers
   */
  @GetMapping("/accounts/{id}/statement")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadStatement(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull String from,
      @RequestParam @NotNull String to,
      @RequestHeader(value = "X-User-Time-Zone", required = false) String userTimeZone) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/org-units/bank/accounts/" + id + "/statement")
            .queryParam("from", from)
            .queryParam("to", to)
            .toUriString();
    try {
      byte[] pdf =
          webClient
              .get()
              .uri(uri)
              .headers(
                  h -> {
                    if (userTimeZone != null && !userTimeZone.isBlank()) {
                      h.set("X-User-Time-Zone", userTimeZone);
                    }
                  })
              .retrieve()
              .bodyToMono(byte[].class)
              .block();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_PDF);
      headers.setContentDispositionFormData("attachment", "kontoauszug-" + id + ".pdf");
      return ResponseEntity.ok().headers(headers).body(pdf);
    } catch (WebClientResponseException e) {
      log.warn("Org-unit statement proxy: backend returned {} for {}", e.getStatusCode(), uri);
      // Relay the backend status, but a generic reason — the raw WebClient message can leak the
      // internal backend URI to the browser (the 500 branch already uses a generic message).
      throw new ResponseStatusException(e.getStatusCode(), "Could not generate the statement.");
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Org-unit statement proxy: unexpected error for {}", uri, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred generating the statement.");
    }
  }

  /**
   * POST helper returning the backend's JSON body as a raw map (empty map for a bodyless 2xx).
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
   * PUT helper returning the backend's JSON body as a raw map (empty map for a bodyless 2xx).
   *
   * @param uri the backend endpoint
   * @param body the forwarded payload
   * @return the backend response body
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> putMap(@NotNull String uri, @NotNull Map<String, Object> body) {
    Map<String, Object> response = backendApiClient.put(uri, body, Map.class);
    return response == null ? Map.of() : response;
  }

  /**
   * DELETE helper returning the backend's JSON body as a raw map (empty map for a bodyless 2xx).
   *
   * @param uri the backend endpoint
   * @return the backend response body
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> deleteMap(@NotNull String uri) {
    Map<String, Object> response = backendApiClient.delete(uri, Map.class);
    return response == null ? Map.of() : response;
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
