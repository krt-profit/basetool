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

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST proxy for the user autocomplete of participant and owner pickers, forwarding to the
 * backend's slim reference search with {@link PickerSearch#PAGE_SIZE} rows sorted by username.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProxyController {

  /** Runs the user searches and lookups on the backend. */
  private final IdentityBackendClient identityClient;

  /**
   * Forwards the autocomplete query to the backend search and returns the hits as a flat list;
   * empty on backend failure.
   *
   * @param query free-text query to forward to the backend, or {@code null}/blank to match all
   * @return matching user references, never {@code null}
   */
  @GetMapping("/search")
  @PreAuthorize("isAuthenticated()")
  public List<UserReferenceDto> searchUsers(@RequestParam(required = false) String query) {
    return content(identityClient.userReferences(query == null ? "" : query));
  }

  /**
   * Bank-audience twin of {@link #searchUsers}, forwarding to {@code
   * /api/v1/users/search-bank/references}, which also admits bank staff (ADR-0089).
   *
   * @param query free-text query to forward to the backend, or {@code null}/blank to match all
   * @return matching user references, never {@code null}
   */
  @GetMapping("/search-bank")
  @PreAuthorize("isAuthenticated()")
  public List<UserReferenceDto> searchUsersForBank(@RequestParam(required = false) String query) {
    return content(identityClient.bankUserReferences(query == null ? "" : query));
  }

  /**
   * Unwraps a search page into a flat list.
   *
   * @param response the backend page, or {@code null}
   * @return the page content, or an empty list when there is none
   */
  private static List<UserReferenceDto> content(@Nullable PageResponse<UserReferenceDto> response) {
    return response != null && response.content() != null ? response.content() : List.of();
  }

  /**
   * Forwards the per-user membership lookup for the bank counterparty org-unit picker
   * (REQ-BANK-044); empty on backend failure.
   *
   * @param userId the counterparty user whose org-unit memberships to list
   * @param allKinds {@code true} to include all four org-unit kinds, else Staffel and SK only
   * @return the user's membership options, never {@code null}
   */
  @GetMapping("/{userId}/memberships")
  @PreAuthorize("isAuthenticated()")
  public List<OrgUnitMembershipOptionDto> userMemberships(
      @PathVariable UUID userId,
      @RequestParam(required = false, defaultValue = "false") boolean allKinds) {
    List<OrgUnitMembershipOptionDto> memberships =
        identityClient.membershipOptions(userId, allKinds);
    return memberships != null ? memberships : List.of();
  }

  /**
   * Resolves one user by id, used to seed a {@code remote-users} combobox in edit mode; the backend
   * enforces the role gate.
   *
   * @param userId the user to resolve; never {@code null}.
   * @return the user, or {@code null} when the lookup fails.
   */
  @Nullable
  @GetMapping("/{userId}")
  @PreAuthorize("isAuthenticated()")
  public UserDto getUser(@PathVariable UUID userId) {
    try {
      return identityClient.user(userId);
    } catch (Exception e) {
      return null;
    }
  }
}
