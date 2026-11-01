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
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarsResponse;
import java.util.Arrays;
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
  void postsTheKeysToDelete() {
    backend.answerJson("{\"favourites\":{\"keys\":[],\"version\":5}}");

    client.prune(List.of("admin.terms"));

    backend.expect("POST", "/api/v1/navigation/preferences/prune", "{\"keys\":[\"admin.terms\"]}");
  }

  @Test
  void readsTheTabBarsOfBothModes() {
    backend.answerJson(
        "{\"tabBars\":{\"member\":{\"slots\":[\"a.one\",null,\"c.three\"],\"version\":2},"
            + "\"admin\":{\"slots\":[null,null,null],\"version\":0}}}");

    TabBarsResponse bars = client.preferences().tabBars();

    assertThat(bars.member().slots()).containsExactly("a.one", null, "c.three");
    assertThat(bars.member().version()).isEqualTo(2);
    assertThat(bars.admin()).isEqualTo(TabBarResponse.AUTOMATIC);
  }

  @Test
  void putsOneModesTabBarWithItsVersion() {
    backend.answerJson("{\"slots\":[\"a.one\",null,null],\"version\":3}");

    TabBarResponse stored =
        client.replaceTabBar(
            NavigationMode.ADMIN, new TabBarUpdateRequest(Arrays.asList("a.one", null, null), 2L));

    assertThat(stored.version()).isEqualTo(3);
    backend.expect(
        "PUT",
        "/api/v1/navigation/preferences/tab-bar/admin",
        "{\"slots\":[\"a.one\",null,null],\"version\":2}");
  }
}
