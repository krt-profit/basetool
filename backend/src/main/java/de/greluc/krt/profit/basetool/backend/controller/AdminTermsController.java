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

import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.backend.service.TermsAcceptanceService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin view of who has and has not accepted the Terms of Use (REQ-SEC-028).
 *
 * <p>Exists because the consent gate is only as good as an operator's ability to see it working:
 * after a terms change every member is blocked until they accept, and without this page the only
 * observable difference between "nobody has logged in yet" and "the gate is broken and nobody can
 * accept" is a flat gauge. The list names the individuals still outstanding so the answer is
 * actionable rather than statistical.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/terms")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(name = "Admin — Terms of Use", description = "Who has accepted the Terms of Use")
public class AdminTermsController {

  /** Accepted values of the {@code filter} query parameter. */
  private static final Set<String> ALLOWED_FILTERS = Set.of("ALL", "ACCEPTED", "PENDING");

  /**
   * Sort fields a caller may name (REQ-API-005). Deliberately narrow: {@code acceptedAt} is the
   * only column of the projection that is not also a user attribute, and allowing arbitrary paths
   * would expose the join's shape to the query string.
   */
  private static final Set<String> ALLOWED_SORT_FIELDS =
      Set.of("username", "displayName", "acceptedAt");

  private final TermsAcceptanceService termsAcceptanceService;

  /**
   * Lists login-capable users with their consent state for the wording currently in force.
   *
   * @param filter {@code ALL} (default), {@code ACCEPTED} or {@code PENDING}
   * @param pageable page, size and sort; sort properties are validated against {@link
   *     #ALLOWED_SORT_FIELDS}
   * @return one page of consent rows, newest-pending-first by default
   * @throws ResponseStatusException {@code 400} if the filter or a sort property is not whitelisted
   */
  @GetMapping
  @Operation(
      summary = "Consent overview",
      description =
          "Lists users who can still sign in together with whether they have accepted the "
              + "Terms-of-Use version currently in force. Accounts whose Keycloak login is already "
              + "gone are omitted — they can never accept, so they would be permanently pending.")
  @ApiResponse(responseCode = "200", description = "One page of consent rows")
  public ResponseEntity<PageResponse<TermsAcceptanceStatusDto>> listAcceptanceStatus(
      @RequestParam(defaultValue = "ALL") @NotNull String filter, @NotNull Pageable pageable) {
    String normalizedFilter = filter.toUpperCase(java.util.Locale.ROOT);
    if (!ALLOWED_FILTERS.contains(normalizedFilter)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "filter must be one of " + ALLOWED_FILTERS);
    }
    validateSort(pageable.getSort());
    return ResponseEntity.ok(
        PageResponse.of(termsAcceptanceService.findAcceptanceStatus(normalizedFilter, pageable)));
  }

  /**
   * Reports how many login-capable users are still missing consent — the headline figure the page
   * leads with, and the number an operator watches fall to zero after a terms change.
   *
   * @return the pending count and the version it refers to
   */
  @GetMapping("/pending-count")
  @Operation(
      summary = "Number of users who have not accepted",
      description =
          "Counts login-capable users missing consent for the version currently in force.")
  @ApiResponse(responseCode = "200", description = "The pending count")
  public ResponseEntity<PendingCountDto> getPendingCount() {
    return ResponseEntity.ok(
        new PendingCountDto(
            termsAcceptanceService.countPendingUsers(), termsAcceptanceService.currentVersion()));
  }

  /**
   * How many users still owe consent, and for which wording.
   *
   * @param pending number of login-capable users without an acceptance for {@code termsVersion}
   * @param termsVersion the content digest of the wording currently in force
   */
  public record PendingCountDto(long pending, String termsVersion) {}

  /**
   * Rejects any sort property outside {@link #ALLOWED_SORT_FIELDS}.
   *
   * @param sort the requested sort
   * @throws ResponseStatusException {@code 400} naming the offending property
   */
  private static void validateSort(@NotNull Sort sort) {
    for (Sort.Order order : sort) {
      if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
        throw new ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "Unsupported sort property: " + order.getProperty());
      }
    }
  }
}
