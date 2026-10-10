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

package de.greluc.krt.profit.basetool.frontend.personalinventory.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.personalinventory.client.PersonalInventoryBackendClient.ItemQuery;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryItemCreateRequest;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryItemUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.personalinventory.model.PersonalInventoryLocationType;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link PersonalInventoryBackendClient} sends (plan F3), each the exact request
 * the personal-inventory controllers sent before the client existed.
 */
class PersonalInventoryBackendClientTest {

  private static final UUID ID = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");
  private static final UUID SUB = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";
  private static final String CREATE_BODY =
      "{\"name\":\"Medpen\",\"note\":null,\"locationUexId\":42,\"locationType\":\"CITY\","
          + "\"quantity\":3}";
  private static final String UPDATE_BODY =
      "{\"name\":\"Medpen\",\"note\":\"Kiste 2\",\"locationUexId\":7,"
          + "\"locationType\":\"SPACE_STATION\",\"quantity\":1,\"version\":4}";

  private BackendClientHarness backend;
  private PersonalInventoryBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new PersonalInventoryBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void ownItemsAndTheLocationSearch() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson("[]");

    assertThat(client.blueprintCountPage()).isNotNull();
    assertThat(client.itemPage(new ItemQuery(2, 50, "name,asc", "Med Pen"))).isNotNull();
    assertThat(client.itemPage(new ItemQuery(null, 50, null, " "))).isNotNull();
    client.create(create());
    client.update(ID, update());
    client.delete(ID);
    assertThat(client.searchLocations("Lor ville", 25)).isEmpty();

    backend.expect("GET", "/api/v1/personal-blueprints?size=1");
    backend.expect("GET", "/api/v1/personal-inventory?page=2&size=50&sort=name%2Casc&q=Med%20Pen");
    backend.expect("GET", "/api/v1/personal-inventory?size=50");
    backend.expect("POST", "/api/v1/personal-inventory", CREATE_BODY);
    backend.expect("PUT", "/api/v1/personal-inventory/" + ID, UPDATE_BODY);
    backend.expect("DELETE", "/api/v1/personal-inventory/" + ID, null);
    backend.expect("GET", "/api/v1/uex/locations/search?q=Lor%20ville&limit=25");
  }

  @Test
  void memberItemsForTheAdminPage() {
    backend.answerEmpty();
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    client.user(SUB);
    assertThat(client.memberItemPage(SUB, new ItemQuery(1, 50, "name,asc", "Med Pen"))).isNotNull();
    assertThat(client.memberItemPage(SUB, new ItemQuery(null, 10, null, null))).isNotNull();
    client.createForMember(SUB, create());
    client.updateForMember(ID, update());
    client.deleteForMember(ID);

    backend.expect("GET", "/api/v1/users/" + SUB);
    backend.expect(
        "GET",
        "/api/v1/admin/personal-inventory/" + SUB + "?page=1&size=50&sort=name,asc&q=Med%20Pen");
    backend.expect("GET", "/api/v1/admin/personal-inventory/" + SUB + "?size=10");
    backend.expect("POST", "/api/v1/admin/personal-inventory/" + SUB, CREATE_BODY);
    backend.expect("PUT", "/api/v1/admin/personal-inventory/items/" + ID, UPDATE_BODY);
    backend.expect("DELETE", "/api/v1/admin/personal-inventory/items/" + ID, null);
  }

  /**
   * Builds the create body both create calls send.
   *
   * @return a three-piece item at a city
   */
  private static PersonalInventoryItemCreateRequest create() {
    return new PersonalInventoryItemCreateRequest(
        "Medpen", null, 42, PersonalInventoryLocationType.CITY, 3);
  }

  /**
   * Builds the update body both update calls send.
   *
   * @return a one-piece item at a station, at version 4
   */
  private static PersonalInventoryItemUpdateRequest update() {
    return new PersonalInventoryItemUpdateRequest(
        "Medpen", "Kiste 2", 7, PersonalInventoryLocationType.SPACE_STATION, 1, 4L);
  }
}
