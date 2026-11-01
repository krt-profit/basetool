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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesAndHiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderModeResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResetRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderScopeUpdateRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link NavigationBackendClient} sends: verb, path and body. */
class NavigationBackendClientTest {

  private BackendClientHarness backend;
  private NavigationBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new NavigationBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void readsTheSections() {
    backend.answerJson(
        "{\"favourites\":{\"keys\":[\"missions.list\"],\"version\":3},"
            + "\"hidden\":{\"keys\":[\"bank.dashboard\"],\"version\":2}}");

    NavigationPreferencesResponse read = client.preferences();

    assertThat(read.favourites()).isEqualTo(new FavouritesResponse(List.of("missions.list"), 3));
    assertThat(read.hidden()).isEqualTo(new HiddenResponse(List.of("bank.dashboard"), 2));
    backend.expect("GET", "/api/v1/navigation/preferences");
  }

  @Test
  void putsTheFavouritesWithTheirVersion() {
    backend.answerJson("{\"keys\":[\"missions.list\",\"orders.list\"],\"version\":4}");

    FavouritesResponse stored =
        client.replaceFavourites(
            new FavouritesUpdateRequest(List.of("missions.list", "orders.list"), 3L));

    assertThat(stored.version()).isEqualTo(4);
    backend.expect(
        "PUT",
        "/api/v1/navigation/preferences/favourites",
        "{\"keys\":[\"missions.list\",\"orders.list\"],\"version\":3}");
  }

  @Test
  void putsTheHiddenPagesWithTheirVersion() {
    backend.answerJson("{\"keys\":[\"bank.dashboard\"],\"version\":2}");

    HiddenResponse stored =
        client.replaceHidden(new HiddenUpdateRequest(List.of("bank.dashboard"), 1L));

    assertThat(stored.version()).isEqualTo(2);
    backend.expect(
        "PUT",
        "/api/v1/navigation/preferences/hidden",
        "{\"keys\":[\"bank.dashboard\"],\"version\":1}");
  }

  @Test
  void putsBothSectionsInOneRequest() {
    backend.answerJson(
        "{\"favourites\":{\"keys\":[],\"version\":4},\"hidden\":{\"keys\":[],\"version\":5}}");

    client.replaceFavouritesAndHidden(
        new FavouritesAndHiddenUpdateRequest(
            new FavouritesUpdateRequest(List.of(), 3L), new HiddenUpdateRequest(List.of(), 4L)));

    backend.expect(
        "PUT",
        "/api/v1/navigation/preferences/favourites-and-hidden",
        "{\"favourites\":{\"keys\":[],\"version\":3},\"hidden\":{\"keys\":[],\"version\":4}}");
  }

  @Test
  void putsTheOrderOfOneScopeWithTheModesVersion() {
    backend.answerJson("{\"scopes\":{\"groups\":[\"trade\",\"bank\"]},\"version\":2}");

    OrderModeResponse stored =
        client.replaceOrderScope(
            "member", "groups", new OrderScopeUpdateRequest(List.of("trade", "bank"), 1L));

    assertThat(stored.version()).isEqualTo(2);
    assertThat(stored.scopes().get("groups")).containsExactly("trade", "bank");
    backend.expect(
        "PUT",
        "/api/v1/navigation/preferences/order/member/groups",
        "{\"keys\":[\"trade\",\"bank\"],\"version\":1}");
  }

  @Test
  void postsTheOrderReset() {
    backend.answerJson("{\"scopes\":{},\"version\":3}");

    client.resetOrder("admin", new OrderResetRequest(2L));

    backend.expect("POST", "/api/v1/navigation/preferences/order/admin/reset", "{\"version\":2}");
  }

  @Test
  void readsTheOrderSectionOfBothModes() {
    backend.answerJson(
        "{\"favourites\":{\"keys\":[],\"version\":0},\"hidden\":{\"keys\":[],\"version\":0},"
            + "\"order\":{\"member\":{\"scopes\":{\"groups\":[\"trade\"]},\"version\":4},"
            + "\"admin\":{\"scopes\":{},\"version\":1}}}");

    OrderResponse order = client.preferences().order();

    assertThat(order.member().version()).isEqualTo(4);
    assertThat(order.member().scopes().get("groups")).containsExactly("trade");
    assertThat(order.admin().version()).isEqualTo(1);
  }

  @Test
  void postsTheKeysToDelete() {
    backend.answerJson("{\"favourites\":{\"keys\":[],\"version\":5}}");

    client.prune(List.of("admin.terms"));

    backend.expect("POST", "/api/v1/navigation/preferences/prune", "{\"keys\":[\"admin.terms\"]}");
  }
}
