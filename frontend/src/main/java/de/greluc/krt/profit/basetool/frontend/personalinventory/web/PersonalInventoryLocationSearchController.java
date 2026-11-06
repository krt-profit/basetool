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

package de.greluc.krt.profit.basetool.frontend.personalinventory.web;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.personalinventory.client.PersonalInventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryLocationOption;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.event.Level;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON proxy behind the KRT-styled location typeahead of the personal-inventory item form: the
 * locations an item may take (REQ-P4K-039). The browser calls the frontend rather than the backend,
 * so the same Spring Security session and CSRF policy apply; a backend failure answers an empty
 * list.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class PersonalInventoryLocationSearchController {

  /** Sends the location searches. */
  private final PersonalInventoryBackendClient personalInventoryClient;

  /**
   * Searches the locations the caller may pick.
   *
   * @param q optional name fragment
   * @param limit optional result cap
   * @return the matching locations, empty when the backend cannot answer
   */
  @GetMapping("/personal-inventory/location-search")
  public List<PersonalInventoryLocationOption> locationSearch(
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
    String query = q == null ? "" : q;
    return load("location-search", () -> personalInventoryClient.searchLocations(query, limit));
  }

  /**
   * Searches the locations a member's items may take, for the admin item form.
   *
   * @param userSub the member's Keycloak {@code sub}
   * @param q optional name fragment
   * @param limit optional result cap
   * @return the matching locations, empty when the backend cannot answer
   */
  @GetMapping("/admin/personal-inventory/{userSub}/location-search")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public List<PersonalInventoryLocationOption> memberLocationSearch(
      @PathVariable @NotNull UUID userSub,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer limit) {
    String query = q == null ? "" : q;
    return load(
        "member-location-search",
        () -> personalInventoryClient.searchMemberLocations(userSub, query, limit));
  }

  /**
   * Runs a search and turns a failure or an empty body into an empty list; the query never reaches
   * the log.
   *
   * @param section the log label of the search
   * @param search the backend call
   * @return the locations, never {@code null}
   */
  @NotNull
  private List<PersonalInventoryLocationOption> load(
      @NotNull String section,
      @NotNull Supplier<@Nullable List<PersonalInventoryLocationOption>> search) {
    List<PersonalInventoryLocationOption> result =
        SectionFallback.load(log, Level.DEBUG, section, search, Collections.emptyList());
    return result == null ? Collections.emptyList() : result;
  }
}
