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
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesAndHiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.PruneRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarUpdateRequest;
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

  private static final String APPEARANCE = PREFERENCES + "/appearance";
  private static final String HIDDEN = PREFERENCES + "/hidden";

  private static final String FAVOURITES_AND_HIDDEN = PREFERENCES + "/favourites-and-hidden";

  private static final String TAB_BAR = PREFERENCES + "/tab-bar/{mode}";

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
   * Replaces the caller's appearance.
   *
   * @param request the new desktop mode and the version the caller last saw
   * @return the stored appearance with the new version, or {@code null} when the backend sent no
   *     body
   */
  @Nullable
  public AppearanceResponse replaceAppearance(@NotNull AppearanceUpdateRequest request) {
    return backendApiClient.put(APPEARANCE, request, AppearanceResponse.class);
  }

  /**
   * Replaces the caller's hidden pages.
   *
   * @param request the new keys and the version the caller last saw
   * @return the stored hidden pages with the new version, or {@code null} when the backend sent no
   *     body
   */
  @Nullable
  public HiddenResponse replaceHidden(@NotNull HiddenUpdateRequest request) {
    return backendApiClient.put(HIDDEN, request, HiddenResponse.class);
  }

  /**
   * Replaces the caller's favourites and hidden pages in one request that checks both versions.
   *
   * @param request both sections' new keys and the versions the caller last saw
   * @return both sections with their new versions, or {@code null} when the backend sent no body
   */
  @Nullable
  public NavigationPreferencesResponse replaceFavouritesAndHidden(
      @NotNull FavouritesAndHiddenUpdateRequest request) {
    return backendApiClient.put(
        FAVOURITES_AND_HIDDEN, request, NavigationPreferencesResponse.class);
  }

  /**
   * Replaces the caller's phone tab bar of one mode.
   *
   * @param mode the mode whose bar changes
   * @param request the three slots and the version the caller last saw
   * @return the stored bar with the new version, or {@code null} when the backend sent no body
   */
  @Nullable
  public TabBarResponse replaceTabBar(
      @NotNull NavigationMode mode, @NotNull TabBarUpdateRequest request) {
    return backendApiClient.put(TAB_BAR, request, TabBarResponse.class, mode.getSegment());
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
