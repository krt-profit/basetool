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

package de.greluc.krt.profit.basetool.frontend.service;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Loads a cached reference catalog through {@link BackendApiClient#getCached} and unwraps its paged
 * envelope into a fresh, mutable list, degrading to an empty list (and logging) on any backend
 * failure. This collapses the "fetch a cached catalog page, take its {@code content()}, fall back
 * to an empty list if the backend is down" idiom that the page controllers each carried verbatim —
 * the hangar's inline ship-type / location / manufacturer catalog loads, the refinery form's
 * private {@code fetchMaterials()} / {@code fetchMethods()} / … copies, and their peers — into one
 * place so a single dead reference catalog degrades identically everywhere and never blanks a whole
 * page.
 *
 * <p>It composes the single {@link BackendApiClient} frontend seam rather than replacing it, so the
 * shared Resilience4j pass still wraps the underlying call. The returned list is always a new
 * {@code ArrayList} the caller may sort in place; callers that need the fetch off the request
 * thread wrap this in {@code parallelPageLoader.loadAsync(...)} exactly as before.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CachedCatalogListLoader {

  private final BackendApiClient backendApiClient;

  /**
   * Fetches a cached catalog page and returns its content as a fresh mutable list, or an empty list
   * when the page (or its content) is absent or the backend call fails.
   *
   * @param <T> the catalog row type
   * @param catalog the cached catalog to read
   * @param pageType the paged-envelope response type for {@code catalog}
   * @param isPublic {@code true} to route through the public (unauthenticated) WebClient, matching
   *     the {@code getCached(..., isPublic)} overload
   * @param errorLabel a short catalog name for the failure log line (e.g. {@code "ship types"})
   * @return a new, mutable list of the catalog rows; empty (never {@code null}) on any failure
   */
  public <T> @NotNull List<T> loadPageContent(
      @NotNull CachedCatalog catalog,
      @NotNull ParameterizedTypeReference<PageResponse<T>> pageType,
      boolean isPublic,
      @NotNull String errorLabel) {
    try {
      PageResponse<T> page = backendApiClient.getCached(catalog, pageType, isPublic);
      if (page != null && page.content() != null) {
        return new ArrayList<>(page.content());
      }
    } catch (Exception e) {
      log.error("Failed to fetch {}", errorLabel, e);
    }
    return new ArrayList<>();
  }
}
