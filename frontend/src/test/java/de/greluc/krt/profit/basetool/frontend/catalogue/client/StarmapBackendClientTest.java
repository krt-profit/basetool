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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapLinkRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.StarmapVisibilityRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link StarmapBackendClient} sends: the object list with only the filters
 * given, the place list with its link filter, the two compare-and-set writes and the storage place
 * create (REQ-P4K-034…038).
 */
class StarmapBackendClientTest {

  private static final String BASE = "/api/v1/catalog/admin/starmap";

  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":25,\"totalElements\":0,\"totalPages\":0}";

  private static final UUID OBJECT = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

  private static final UUID ROW = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

  private BackendClientHarness backend;
  private StarmapBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new StarmapBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void theObjectList_sendsOnlyTheFiltersGiven() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.objects(null, null, null, 0, 25);
    client.objects("Stanton", "Moon", "Daymar & co", 2, 25);

    backend.expect("GET", BASE + "/objects?page=0&size=25");
    backend.expect(
        "GET",
        BASE + "/objects?page=2&size=25&starSystem=Stanton&type=Moon&query=Daymar%20%26%20co");
  }

  @Test
  void thePlaceList_carriesTheKindTheLinkFilterAndTheOptionalSearch() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.places("CITY", null, false, 0, 25);
    client.places("SPACE_STATION", "Port", true, 1, 25);

    backend.expect("GET", BASE + "/places/CITY?page=0&size=25&unlinkedOnly=false");
    backend.expect(
        "GET", BASE + "/places/SPACE_STATION?page=1&size=25&unlinkedOnly=true&query=Port");
  }

  @Test
  void theVisibilityOverride_putsTheOverrideAndTheVersion() {
    backend.answerJson("{}");

    client.overrideVisibility(OBJECT, new StarmapVisibilityRequest("HIDDEN", 3L));

    String body =
        backend
            .expect("PUT", BASE + "/objects/" + OBJECT + "/visibility")
            .getBody()
            .readString(StandardCharsets.UTF_8);
    assertThat(body).contains("\"override\":\"HIDDEN\"").contains("\"version\":3");
  }

  @Test
  void aLinkChange_putsTheActionAndTheLinkTheAdminSaw() {
    backend.answerJson("{}");

    client.changeLink("CITY", ROW, new StarmapLinkRequest("SET", OBJECT, null, false));

    String body =
        backend
            .expect("PUT", BASE + "/places/CITY/" + ROW + "/link")
            .getBody()
            .readString(StandardCharsets.UTF_8);
    assertThat(body)
        .contains("\"action\":\"SET\"")
        .contains("\"starmapObjectId\":\"" + OBJECT + "\"")
        .contains("\"expectedManual\":false");
  }

  @Test
  void aStoragePlace_isPostedAsALocationOnItsObject() {
    backend.answerJson("{}");

    client.createStoragePlace(
        new LocationDto(null, "Lager Lorville", null, false, true, null, OBJECT, null, null));

    String body =
        backend.expect("POST", "/api/v1/locations").getBody().readString(StandardCharsets.UTF_8);
    assertThat(body)
        .contains("\"name\":\"Lager Lorville\"")
        .contains("\"homeLocation\":true")
        .contains("\"starmapObjectId\":\"" + OBJECT + "\"");
  }
}
