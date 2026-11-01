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

package de.greluc.krt.profit.basetool.frontend.navigation.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.PruneRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the navigation domain: the caller's own preferences (REQ-UI-032), over
 * {@link BackendApiClient}.
 */
@Service
@RequiredArgsConstructor
public class NavigationBackendClient {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";

  private static final String FAVOURITES = PREFERENCES + "/favourites";

  private static final String PRUNE = PREFERENCES + "/prune";

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads every preference section of the caller.
   *
   * @return the sections, or {@code null} when the backend sent no body
   */
  @Nullable
  public NavigationPreferencesResponse preferences() {
    return backendApiClient.get(PREFERENCES, NavigationPreferencesResponse.class);
  }

  /**
   * Replaces the caller's favourites.
   *
   * @param request the new keys and the version the caller last saw
   * @return the stored favourites with the new version, or {@code null} when the backend sent no
   *     body
   */
  @Nullable
  public FavouritesResponse replaceFavourites(@NotNull FavouritesUpdateRequest request) {
    return backendApiClient.put(FAVOURITES, request, FavouritesResponse.class);
  }

  /**
   * Deletes entry keys the caller may no longer see from every section.
   *
   * @param keys the entry keys to delete
   * @return the sections after the deletion, or {@code null} when the backend sent no body
   */
  @Nullable
  public NavigationPreferencesResponse prune(@NotNull List<String> keys) {
    return backendApiClient.post(
        PRUNE, new PruneRequest(keys), NavigationPreferencesResponse.class);
  }
}
