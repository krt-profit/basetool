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
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites.Item;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationHidden;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationOrder;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPersonalisation;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderModeResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link NavigationPreferencesReader}: availability, deletion and failing closed. */
class NavigationPreferencesReaderTest {

  private static final NavigationAccess MEMBER =
      new NavigationAccess(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

  private NavigationBackendClient client;
  private NavigationPreferencesReader reader;

  @BeforeEach
  void setUp() {
    client = mock(NavigationBackendClient.class);
    reader = new NavigationPreferencesReader(client, new NavigationRegistry());
  }

  private static NavigationPreferencesResponse stored(long version, String... keys) {
    return new NavigationPreferencesResponse(
        new FavouritesResponse(List.of(keys), version), HiddenResponse.EMPTY, OrderResponse.EMPTY);
  }

  private static NavigationPreferencesResponse storedHidden(long version, String... keys) {
    return new NavigationPreferencesResponse(
        new FavouritesResponse(List.of(), 0),
        new HiddenResponse(List.of(keys), version),
        OrderResponse.EMPTY);
  }

  private static List<String> keys(List<Item> items) {
    return items.stream().map(item -> item.entry().key()).toList();
  }

  private static NavigationPreferencesResponse storedOrder(OrderModeResponse member) {
    return new NavigationPreferencesResponse(
        new FavouritesResponse(List.of(), 0),
        HiddenResponse.EMPTY,
        new OrderResponse(member, OrderModeResponse.EMPTY));
  }

  @Test
  @DisplayName("a stored order of known groups and permitted links is rendered as stored")
  void rendersTheStoredOrder() {
    when(client.preferences())
        .thenReturn(
            storedOrder(
                new OrderModeResponse(
                    Map.of(
                        "groups", List.of("logistics", "operations"),
                        "operations", List.of("hangar.overview", "missions.list")),
                    6)));

    NavigationOrder.Mode order = reader.read(MEMBER).order().member();

    assertThat(order.version()).isEqualTo(6);
    assertThat(order.groups()).containsExactly("logistics", "operations");
    assertThat(order.links("operations")).containsExactly("hangar.overview", "missions.list");
    verify(client, never()).prune(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  @DisplayName("an order key the registry does not know or the caller lost the role for is deleted")
  void deletesOrderKeysThatAreGone() {
    when(client.preferences())
        .thenReturn(
            storedOrder(
                new OrderModeResponse(
                    Map.of(
                        "groups", List.of("operations", "retired-group"),
                        "operations", List.of("missions.list", "retired.page"),
                        "unit", List.of("admin.terms")),
                    3)));
    when(client.prune(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(
            storedOrder(
                new OrderModeResponse(
                    Map.of(
                        "groups", List.of("operations"),
                        "operations", List.of("missions.list")),
                    4)));

    NavigationOrder.Mode order = reader.read(MEMBER).order().member();

    verify(client)
        .prune(
            org.mockito.ArgumentMatchers.argThat(gone ->
                gone.containsAll(List.of("retired-group", "retired.page", "admin.terms"))
                    && gone.size() == 3));
    assertThat(order.version()).isEqualTo(4);
    assertThat(order.groups()).containsExactly("operations");
  }

  @Test
  @DisplayName("an entry only the active context refuses keeps its place in the stored order")
  void keepsAContextOnlyOrderKey() {
    when(client.preferences())
        .thenReturn(
            storedOrder(new OrderModeResponse(Map.of("logistics", List.of("orders.list")), 1)));

    assertThat(reader.read(MEMBER).order().member().links("logistics"))
        .containsExactly("orders.list");
    verify(client, never()).prune(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  @DisplayName("an entry the caller may open renders available, in the stored order")
  void rendersAvailableEntriesInOrder() {
    when(client.preferences()).thenReturn(stored(2, "refinery.orders", "missions.list"));

    NavigationFavourites favourites = reader.read(MEMBER).favourites();

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

    NavigationFavourites favourites = reader.read(MEMBER).favourites();

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

    NavigationFavourites favourites = reader.read(MEMBER).favourites();

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
        reader.read(new NavigationAccess(Set.of("ADMIN", "KRT_MEMBER"), Set.of())).favourites();

    assertThat(keys(favourites.admin())).containsExactly("admin.terms");
    assertThat(keys(favourites.member())).containsExactly("missions.list");
    assertThat(favourites.keys()).containsExactly("admin.terms", "missions.list");
  }

  @Test
  @DisplayName("a key the registry does not know is deleted and no longer counts")
  void deletesAnUnknownKey() {
    when(client.preferences()).thenReturn(stored(1, "future.page", "missions.list"));
    when(client.prune(List.of("future.page"))).thenReturn(stored(2, "missions.list"));

    NavigationFavourites favourites = reader.read(MEMBER).favourites();

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

    assertThat(reader.read(MEMBER).favourites().full()).isTrue();
  }

  @Test
  @DisplayName("a hidden page renders in its mode's Mehr list in registry order, with the version")
  void rendersHiddenPagesInRegistryOrder() {
    when(client.preferences()).thenReturn(storedHidden(3, "refinery.orders", "missions.list"));

    NavigationHidden hidden = reader.read(MEMBER).hidden();

    assertThat(hidden.loaded()).isTrue();
    assertThat(hidden.version()).isEqualTo(3);
    assertThat(hidden.keys()).containsExactly("missions.list", "refinery.orders");
    assertThat(hidden.member()).allMatch(NavigationHidden.Item::available);
    assertThat(hidden.admin()).isEmpty();
  }

  @Test
  @DisplayName("a page the active context refuses stays hidden and renders unavailable")
  void keepsAHiddenContextGapStored() {
    when(client.preferences()).thenReturn(storedHidden(1, "orders.list"));

    NavigationHidden hidden = reader.read(MEMBER).hidden();

    assertThat(hidden.keys()).containsExactly("orders.list");
    assertThat(hidden.member())
        .singleElement()
        .satisfies(item -> assertThat(item.available()).isFalse());
    verify(client, never()).prune(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  @DisplayName("a hidden key that is no page, is unknown or lost its role is deleted")
  void deletesHiddenKeysThatAreNoLongerPages() {
    when(client.preferences())
        .thenReturn(
            storedHidden(2, "missions.list", "operations.list", "future.page", "admin.terms"));
    when(client.prune(List.of("future.page", "operations.list", "admin.terms")))
        .thenReturn(storedHidden(3, "missions.list"));

    NavigationHidden hidden = reader.read(MEMBER).hidden();

    verify(client).prune(List.of("future.page", "operations.list", "admin.terms"));
    assertThat(hidden.keys()).containsExactly("missions.list");
    assertThat(hidden.version()).isEqualTo(3);
  }

  @Test
  @DisplayName("an administrator's hidden pages split by mode")
  void splitsHiddenAdminPagesFromMemberPages() {
    when(client.preferences()).thenReturn(storedHidden(1, "admin.members", "missions.list"));

    NavigationHidden hidden =
        reader.read(new NavigationAccess(Set.of("ADMIN", "KRT_MEMBER"), Set.of())).hidden();

    assertThat(keysOfHidden(hidden.admin())).containsExactly("admin.members");
    assertThat(keysOfHidden(hidden.member())).containsExactly("missions.list");
  }

  private static List<String> keysOfHidden(List<NavigationHidden.Item> items) {
    return items.stream().map(item -> item.entry().key()).toList();
  }

  @Test
  @DisplayName("a failed read renders no favourite and leaves the stars off")
  void failsClosed() {
    when(client.preferences()).thenThrow(new BackendServiceException("backend down", null, 503));

    assertThat(reader.read(MEMBER)).isEqualTo(NavigationPersonalisation.NOT_LOADED);
    assertThat(NavigationFavourites.NOT_LOADED.loaded()).isFalse();
  }

  @Test
  @DisplayName("an empty answer renders no favourite")
  void failsClosedOnAnEmptyAnswer() {
    when(client.preferences()).thenReturn(null);

    assertThat(reader.read(MEMBER)).isEqualTo(NavigationPersonalisation.NOT_LOADED);
  }

  @Test
  @DisplayName("a lost session still propagates to the global handler")
  void propagatesAReauthentication() {
    when(client.preferences()).thenThrow(new ReauthenticationRequiredException("session", null));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> reader.read(MEMBER))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }
}
