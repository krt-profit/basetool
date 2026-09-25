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

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST proxy for the user autocomplete of participant and owner pickers, forwarding to the
 * backend's slim reference search via {@link BackendApiClient} with {@link PickerSearch#PAGE_SIZE}
 * rows sorted by username (see {@link #forwardSearch}).
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProxyController {

  /**
   * Response type for the paged user-autocomplete search ({@code /api/v1/users/search}), whose raw
   * JSON rows are decoded as maps.
   */
  private static final ParameterizedTypeReference<PageResponse<Map<String, Object>>>
      USER_SEARCH_PAGE = new ParameterizedTypeReference<>() {};

  /**
   * Response type for the per-user org-unit membership lookup ({@code
   * /api/v1/users/{userId}/memberships}), whose raw option rows are decoded as maps.
   */
  private static final ParameterizedTypeReference<List<Map<String, Object>>>
      MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  /**
   * Response type for the single-user lookup ({@code /api/v1/users/{id}}), whose raw JSON is
   * decoded as a map.
   */
  private static final ParameterizedTypeReference<Map<String, Object>> USER_MAP =
      new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Forwards the autocomplete query to the backend search and returns the hits as a flat list;
   * empty on backend failure.
   *
   * @param query free-text query to forward to the backend, or {@code null}/blank to match all
   * @return matching user records (raw JSON maps), never {@code null}
   */
  @GetMapping("/search")
  @PreAuthorize("isAuthenticated()")
  public List<Map<String, Object>> searchUsers(@RequestParam(required = false) String query) {
    return forwardSearch("/api/v1/users/search/references", query);
  }

  /**
   * Bank-audience twin of {@link #searchUsers}, forwarding to {@code
   * /api/v1/users/search-bank/references}, which also admits bank staff (ADR-0089).
   *
   * @param query free-text query to forward to the backend, or {@code null}/blank to match all
   * @return matching user records (raw JSON maps), never {@code null}
   */
  @GetMapping("/search-bank")
  @PreAuthorize("isAuthenticated()")
  public List<Map<String, Object>> searchUsersForBank(
      @RequestParam(required = false) String query) {
    return forwardSearch("/api/v1/users/search-bank/references", query);
  }

  /**
   * Forwards a search to the backend with a properly encoded URI (via {@link
   * org.springframework.web.util.UriComponentsBuilder}) and unwraps the page into a flat list;
   * empty on backend failure.
   *
   * <p>A {@code null} query becomes the empty match-all filter. The page size is {@link
   * PickerSearch#PAGE_SIZE}, one more than {@link PickerSearch#RENDER_CAP}, as the overflow
   * sentinel (REQ-FE-016).
   *
   * @param backendPath the backend search endpoint path to forward to
   * @param query the free-text query to forward, or {@code null}/blank to match all
   * @return matching user records (raw JSON maps), never {@code null}
   */
  private List<Map<String, Object>> forwardSearch(String backendPath, String query) {
    String uri =
        org.springframework.web.util.UriComponentsBuilder.fromPath(backendPath)
            .queryParam("size", PickerSearch.PAGE_SIZE)
            .queryParam("sort", "username,asc")
            .toUriString();
    PageResponse<Map<String, Object>> response =
        backendApiClient.get(uri + "&query={query}", USER_SEARCH_PAGE, query == null ? "" : query);
    return response != null && response.content() != null ? response.content() : List.of();
  }

  /**
   * Forwards the per-user membership lookup for the bank counterparty org-unit picker
   * (REQ-BANK-044); empty on backend failure.
   *
   * @param userId the counterparty user whose org-unit memberships to list
   * @param allKinds {@code true} to include all four org-unit kinds, else Staffel and SK only
   * @return the user's membership options (raw JSON maps), never {@code null}
   */
  @GetMapping("/{userId}/memberships")
  @PreAuthorize("isAuthenticated()")
  public List<Map<String, Object>> userMemberships(
      @PathVariable UUID userId,
      @RequestParam(required = false, defaultValue = "false") boolean allKinds) {
    String uri =
        org.springframework.web.util.UriComponentsBuilder.fromPath(
                "/api/v1/users/" + userId + "/memberships")
            .queryParam("allKinds", allKinds)
            .toUriString();
    List<Map<String, Object>> memberships = backendApiClient.get(uri, MEMBERSHIP_OPTION_LIST);
    return memberships != null ? memberships : List.of();
  }

  /**
   * Resolves one user by id to their raw JSON, used to seed a {@code remote-users} combobox in edit
   * mode; the backend enforces the role gate.
   *
   * @param userId the user to resolve; never {@code null}.
   * @return the user's raw JSON map, or {@code null} when the lookup fails.
   */
  @Nullable
  @GetMapping("/{userId}")
  @PreAuthorize("isAuthenticated()")
  public Map<String, Object> getUser(@PathVariable UUID userId) {
    try {
      return backendApiClient.get("/api/v1/users/" + userId, USER_MAP);
    } catch (Exception e) {
      return null;
    }
  }
}
