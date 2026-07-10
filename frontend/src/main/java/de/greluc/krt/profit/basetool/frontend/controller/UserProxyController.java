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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST proxy for the user-autocomplete used by participant/owner pickers.
 *
 * <p>Browser-side JS calls land on {@code /users/search} (no {@code /api/} prefix because Spring
 * Security treats this path as authenticated-only-by-default); the controller forwards to the
 * backend {@code /api/v1/users/search} with the bearer token attached by {@link BackendApiClient}.
 * The page size is hardcoded at 1000 and sorted by username — autocomplete lists are short, so a
 * single page covers any realistic squadron.
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
   * Forwards the autocomplete query to the backend search endpoint and unwraps the page payload
   * into a flat list. Empty list on backend failure or missing content — the autocomplete renders
   * an empty result rather than surfacing the error.
   *
   * @param query free-text query to forward to the backend
   * @return matching user records (raw JSON maps), never {@code null}
   */
  @GetMapping("/search")
  @PreAuthorize("isAuthenticated()")
  public List<Map<String, Object>> searchUsers(@RequestParam String query) {
    return forwardSearch("/api/v1/users/search", query);
  }

  /**
   * Bank-audience twin of {@link #searchUsers}: forwards to the backend {@code
   * /api/v1/users/search-bank}, which mirrors {@code /search} but widens the role gate to bank
   * staff (ADR-0089, #1193). Backs the bank pickers' {@code remote-bank-users} combobox source
   * (register holder, grant the Bank-Employee role, approval limits) so a bank employee/manager who
   * holds no org role can still resolve candidates. The real authorization is enforced by the
   * backend; this proxy only requires an authenticated session.
   *
   * @param query free-text query to forward to the backend
   * @return matching user records (raw JSON maps), never {@code null}
   */
  @GetMapping("/search-bank")
  @PreAuthorize("isAuthenticated()")
  public List<Map<String, Object>> searchUsersForBank(@RequestParam String query) {
    return forwardSearch("/api/v1/users/search-bank", query);
  }

  /**
   * Shared body of the two search proxies: builds the properly-encoded backend URI (via {@link
   * org.springframework.web.util.UriComponentsBuilder} so a crafted {@code &} in the query cannot
   * inject extra parameters — L-1), forwards it, and unwraps the page payload into a flat list.
   * Empty list on backend failure or missing content — the autocomplete renders an empty result
   * rather than surfacing the error.
   *
   * @param backendPath the backend search endpoint path to forward to
   * @param query the free-text query to forward
   * @return matching user records (raw JSON maps), never {@code null}
   */
  private List<Map<String, Object>> forwardSearch(String backendPath, String query) {
    String uri =
        org.springframework.web.util.UriComponentsBuilder.fromPath(backendPath)
            .queryParam("query", query)
            .queryParam("size", 1000)
            .queryParam("sort", "username,asc")
            .toUriString();
    PageResponse<Map<String, Object>> response = backendApiClient.get(uri, USER_SEARCH_PAGE);
    return response != null && response.content() != null ? response.content() : List.of();
  }

  /**
   * Forwards the per-user membership lookup to the backend so the bank booking form's counterparty
   * org-unit picker (REQ-BANK-044) can populate its dependent {@code <select>} from the chosen
   * counterparty's memberships. Returns the raw option maps ({@code orgUnitId}, {@code
   * orgUnitName}, {@code orgUnitShorthand}, {@code kind}); an empty list on backend failure so the
   * picker degrades to "no org unit" rather than surfacing an error. The {@link UUID} path type
   * rejects a malformed id before any backend call.
   *
   * @param userId the counterparty user whose org-unit memberships to list
   * @param allKinds when {@code true} forwards {@code allKinds=true} so the response spans all four
   *     org-unit kinds (the bank counterparty picker; REQ-BANK-044), else the default Staffel/SK
   *     set
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
   * Resolves a single user by id to their raw JSON (id, username, displayName, effectiveName, …).
   * Backs the edit-mode seed of the {@code remote-users} combobox where the picker holds only a
   * stored user id and must display that user's name without preloading the roster — the
   * notification-rule SPECIFIC_USER selector (#1193). The backend {@code /api/v1/users/{id}}
   * enforces the real role gate (and peer redaction); this proxy only requires an authenticated
   * session and returns {@code null} on any failure so the caller can fall back to the raw id. The
   * {@link UUID} path type rejects a malformed id before any backend call — so it never shadows the
   * literal {@code /search} / {@code /search-bank} routes.
   *
   * @param userId the user to resolve; never {@code null}.
   * @return the user's raw JSON map, or {@code null} when the lookup fails.
   */
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
