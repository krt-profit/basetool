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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.navigation.client.NavigationBackendClient;
import de.greluc.krt.profit.basetool.frontend.navigation.model.AppearanceResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.DesktopMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites.Item;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link NavigationFavouritesReader}: availability, deletion and failing closed. */
class NavigationFavouritesReaderTest {

  private static final NavigationAccess MEMBER =
      new NavigationAccess(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

  private NavigationBackendClient client;
  private NavigationFavouritesReader reader;

  @BeforeEach
  void setUp() {
    client = mock(NavigationBackendClient.class);
    reader =
        new NavigationFavouritesReader(
            new NavigationPreferencesLoader(client), client, new NavigationRegistry());
  }

  private static NavigationPreferencesResponse stored(long version, String... keys) {
    return new NavigationPreferencesResponse(
        new FavouritesResponse(List.of(keys), version),
        new AppearanceResponse(DesktopMode.DRAWER, 0));
  }

  private static List<String> keys(List<Item> items) {
    return items.stream().map(item -> item.entry().key()).toList();
  }

  @Test
  @DisplayName("an entry the caller may open renders available, in the stored order")
  void rendersAvailableEntriesInOrder() {
    when(client.preferences()).thenReturn(stored(2, "refinery.orders", "missions.list"));

    NavigationFavourites favourites = reader.read(MEMBER);

    assertThat(favourites.loaded()).isTrue();
    assertThat(favourites.version()).isEqualTo(2);
    assertThat(keys(favourites.member())).containsExactly("refinery.orders", "missions.list");
    assertThat(favourites.member()).allMatch(Item::available);
    assertThat(favourites.admin()).isEmpty();
  }

  @Test
  @DisplayName("an entry only the active context refuses stays stored and renders unavailable")
  void keepsAContextOnlyGapGreyedAndStored() {
    when(client.preferences()).thenReturn(stored(1, "orders.list"));

    NavigationFavourites favourites = reader.read(MEMBER);

    assertThat(favourites.member())
        .singleElement()
        .satisfies(item -> {
          assertThat(item.entry().key()).isEqualTo("orders.list");
          assertThat(item.available()).isFalse();
        });
    assertThat(favourites.keys()).containsExactly("orders.list");
    verify(client, never()).prune(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  @DisplayName("an entry whose role the caller lost is deleted and no longer rendered")
  void deletesAnEntryWhoseRoleIsGone() {
    when(client.preferences()).thenReturn(stored(4, "missions.list", "admin.terms"));
    when(client.prune(List.of("admin.terms"))).thenReturn(stored(5, "missions.list"));

    NavigationFavourites favourites = reader.read(MEMBER);

    verify(client).prune(List.of("admin.terms"));
    assertThat(favourites.version()).isEqualTo(5);
    assertThat(favourites.keys()).containsExactly("missions.list");
    assertThat(keys(favourites.member())).containsExactly("missions.list");
    assertThat(favourites.admin()).isEmpty();
  }

  @Test
  @DisplayName("an administrator's favourites split by mode and share one list")
  void splitsAdminEntriesFromMemberEntries() {
    when(client.preferences()).thenReturn(stored(1, "admin.terms", "missions.list"));

    NavigationFavourites favourites =
        reader.read(new NavigationAccess(Set.of("ADMIN", "KRT_MEMBER"), Set.of()));

    assertThat(keys(favourites.admin())).containsExactly("admin.terms");
    assertThat(keys(favourites.member())).containsExactly("missions.list");
    assertThat(favourites.keys()).containsExactly("admin.terms", "missions.list");
  }

  @Test
  @DisplayName("a key the registry does not know is deleted and no longer counts")
  void deletesAnUnknownKey() {
    when(client.preferences()).thenReturn(stored(1, "future.page", "missions.list"));
    when(client.prune(List.of("future.page"))).thenReturn(stored(2, "missions.list"));

    NavigationFavourites favourites = reader.read(MEMBER);

    verify(client).prune(List.of("future.page"));
    assertThat(keys(favourites.member())).containsExactly("missions.list");
    assertThat(favourites.keys()).containsExactly("missions.list");
  }

  @Test
  @DisplayName("the cap counts every stored key")
  void reportsTheCap() {
    String[] twelve = {
      "missions.list", "hangar.overview", "storage.overview", "refinery.orders",
      "operations.list", "hangar.catalog", "hangar.unit", "storage.levels",
      "storage.my", "storage.all", "personal.inventory", "personal.blueprints"
    };
    when(client.preferences()).thenReturn(stored(1, twelve));

    assertThat(reader.read(MEMBER).full()).isTrue();
  }

  @Test
  @DisplayName("a failed read renders no favourite and leaves the stars off")
  void failsClosed() {
    when(client.preferences()).thenThrow(new BackendServiceException("backend down", null, 503));

    assertThat(reader.read(MEMBER)).isEqualTo(NavigationFavourites.NOT_LOADED);
    assertThat(NavigationFavourites.NOT_LOADED.loaded()).isFalse();
  }

  @Test
  @DisplayName("an empty answer renders no favourite")
  void failsClosedOnAnEmptyAnswer() {
    when(client.preferences()).thenReturn(null);

    assertThat(reader.read(MEMBER)).isEqualTo(NavigationFavourites.NOT_LOADED);
  }

  @Test
  @DisplayName("a lost session still propagates to the global handler")
  void propagatesAReauthentication() {
    when(client.preferences()).thenThrow(new ReauthenticationRequiredException("session", null));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> reader.read(MEMBER))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }
}
