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

package de.greluc.krt.profit.basetool.frontend.hangar.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.hangar.model.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.SetHomeLocationRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipBulkOrgUnitRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipBulkOrgUnitResultDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipOrgUnitRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipRequestDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Pins the requests {@link HangarBackendClient} sends (plan F3), each the exact verb, path, query
 * and body the hangar controllers sent before the client existed.
 */
class HangarBackendClientTest {

  private static final UUID SHIP = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
  private static final UUID SHIP_TYPE = UUID.fromString("1b2c3d4e-5f6a-4b7c-9d8e-0f1a2b3c4d5e");
  private static final UUID LOCATION = UUID.fromString("2c3d4e5f-6a7b-4c8d-8e9f-1a2b3c4d5e6f");
  private static final UUID UNIT = UUID.fromString("3d4e5f6a-7b8c-4d9e-9f0a-2b3c4d5e6f7a");
  private static final UUID MEMBER = UUID.fromString("4e5f6a7b-8c9d-4e0f-8a1b-3c4d5e6f7a8b");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";
  private static final String IMPORT_RESULT =
      "{\"importedCount\":2,\"skippedCount\":1,\"duplicateCount\":0,"
          + "\"skippedShips\":[\"Odd\"],\"duplicateShips\":[]}";

  private BackendClientHarness backend;
  private HangarBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new HangarBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void pageReadsCarryPagingFittedAndAnEncodedSearch() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");

    client.myShips(2, 50, Boolean.FALSE, "Cutlass & Co");
    client.myShips(0, 1, null, " ");
    client.squadronOverview(1, 10, "Cutlass & Co");
    client.squadronOverview(0, 1, null);
    assertThat(client.homeLocations()).isEmpty();
    assertThat(client.pickableOrgUnits()).isEmpty();

    backend.expect(
        "GET", "/api/v1/hangar/my-ships?page=2&size=50&fitted=false&search=Cutlass%20%26%20Co");
    backend.expect("GET", "/api/v1/hangar/my-ships?page=0&size=1");
    backend.expect(
        "GET", "/api/v1/hangar/squadron-overview?page=1&size=10&search=Cutlass%20%26%20Co");
    backend.expect("GET", "/api/v1/hangar/squadron-overview?page=0&size=1");
    backend.expect("GET", "/api/v1/locations/home-locations");
    backend.expect("GET", "/api/v1/org-units/me/pickable");
  }

  @Test
  void unitChangeReadsTheDirectUnitsAndPutsTheUnitWithItsCounter() {
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");

    assertThat(client.directOrgUnits()).isEmpty();
    client.changeShipOrgUnit(SHIP, new ShipOrgUnitRequestDto(UNIT, 2L));
    client.changeShipOrgUnit(SHIP, new ShipOrgUnitRequestDto(null, 3L));

    backend.expect("GET", "/api/v1/org-units/me/memberships?allKinds=true");
    backend.expect(
        "PUT",
        "/api/v1/hangar/ships/" + SHIP + "/org-unit",
        "{\"orgUnitId\":\"" + UNIT + "\",\"owningOrgUnitVersion\":2}");
    backend.expect(
        "PUT",
        "/api/v1/hangar/ships/" + SHIP + "/org-unit",
        "{\"orgUnitId\":null,\"owningOrgUnitVersion\":3}");
  }

  @Test
  void bulkUnitChangePostsTheSelectionAndTheTarget() {
    backend.answerJson("{\"changed\":1,\"skipped\":0}");

    ShipBulkOrgUnitResultDto result =
        client.bulkChangeShipOrgUnit(new ShipBulkOrgUnitRequestDto(List.of(SHIP), UNIT));

    assertThat(result).isEqualTo(new ShipBulkOrgUnitResultDto(1, 0));
    backend.expect(
        "POST",
        "/api/v1/hangar/ships/bulk-org-unit",
        "{\"shipIds\":[\"" + SHIP + "\"],\"orgUnitId\":\"" + UNIT + "\"}");
  }

  @Test
  void shipWrites() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    client.createShip(new ShipRequestDto("Kestrel", SHIP_TYPE, "LTI", LOCATION, true, null, UNIT));
    client.updateShip(SHIP, new ShipRequestDto("Kestrel", SHIP_TYPE, "6", null, false, 3L, null));
    client.deleteShip(SHIP);
    client.setHomeLocation(new SetHomeLocationRequestDto(LOCATION));
    client.deleteAllShips();

    backend.expect(
        "POST",
        "/api/v1/hangar/ships",
        "{\"name\":\"Kestrel\",\"shipTypeId\":\""
            + SHIP_TYPE
            + "\",\"insurance\":\"LTI\",\"locationId\":\""
            + LOCATION
            + "\",\"fitted\":true,\"version\":null,\"owningOrgUnitId\":\""
            + UNIT
            + "\"}");
    backend.expect(
        "PUT",
        "/api/v1/hangar/ships/" + SHIP,
        "{\"name\":\"Kestrel\",\"shipTypeId\":\""
            + SHIP_TYPE
            + "\",\"insurance\":\"6\",\"locationId\":null,\"fitted\":false,\"version\":3,"
            + "\"owningOrgUnitId\":null}");
    backend.expect("DELETE", "/api/v1/hangar/ships/" + SHIP, null);
    backend.expect(
        "POST", "/api/v1/hangar/ships/home-location", "{\"locationId\":\"" + LOCATION + "\"}");
    backend.expect("DELETE", "/api/v1/hangar/ships", null);
  }

  @Test
  void memberHangarReadsAndWritesGoThroughTheAdminPathsOfThatMember() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{\"id\":\"" + MEMBER + "\"}");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();

    client.memberShips(MEMBER, 1, 10);
    client.member(MEMBER);
    assertThat(client.memberOrgUnits(MEMBER)).isEmpty();
    client.createMemberShip(
        MEMBER, new ShipRequestDto(null, SHIP_TYPE, "LTI", null, false, null, UNIT));
    client.updateMemberShip(
        MEMBER, SHIP, new ShipRequestDto("Kestrel", SHIP_TYPE, "0", LOCATION, true, 4L, null));
    client.deleteMemberShip(MEMBER, SHIP);

    backend.expect("GET", "/api/v1/hangar/admin/users/" + MEMBER + "/ships?page=1&size=10");
    backend.expect("GET", "/api/v1/users/" + MEMBER);
    backend.expect("GET", "/api/v1/org-units/members/" + MEMBER + "/memberships?allKinds=true");
    backend.expect(
        "POST",
        "/api/v1/hangar/admin/users/" + MEMBER + "/ships",
        "{\"name\":null,\"shipTypeId\":\""
            + SHIP_TYPE
            + "\",\"insurance\":\"LTI\",\"locationId\":null,\"fitted\":false,\"version\":null,"
            + "\"owningOrgUnitId\":\""
            + UNIT
            + "\"}");
    backend.expect(
        "PUT",
        "/api/v1/hangar/admin/users/" + MEMBER + "/ships/" + SHIP,
        "{\"name\":\"Kestrel\",\"shipTypeId\":\""
            + SHIP_TYPE
            + "\",\"insurance\":\"0\",\"locationId\":\""
            + LOCATION
            + "\",\"fitted\":true,\"version\":4,\"owningOrgUnitId\":null}");
    backend.expect("DELETE", "/api/v1/hangar/admin/users/" + MEMBER + "/ships/" + SHIP, null);
  }

  @Test
  void memberUnitChangesGoThroughTheAdminPathsOfThatMember() {
    backend.answerJson("{}");
    backend.answerJson("{\"changed\":2,\"skipped\":0}");

    client.changeMemberShipOrgUnit(MEMBER, SHIP, new ShipOrgUnitRequestDto(UNIT, 3L));
    assertThat(
            client.bulkChangeMemberShipOrgUnit(
                MEMBER, new ShipBulkOrgUnitRequestDto(List.of(SHIP), null)))
        .isEqualTo(new ShipBulkOrgUnitResultDto(2, 0));

    backend.expect(
        "PUT",
        "/api/v1/hangar/admin/users/" + MEMBER + "/ships/" + SHIP + "/org-unit",
        "{\"orgUnitId\":\"" + UNIT + "\",\"owningOrgUnitVersion\":3}");
    backend.expect(
        "POST",
        "/api/v1/hangar/admin/users/" + MEMBER + "/ships/bulk-org-unit",
        "{\"shipIds\":[\"" + SHIP + "\"],\"orgUnitId\":null}");
  }

  @Test
  void importsStreamTheUploadAsTheFilePart() {
    backend.answerJson(IMPORT_RESULT);

    FleetviewImportResponseDto ships = client.importShips(upload("shiplist.json"));

    assertThat(ships).isEqualTo(new FleetviewImportResponseDto(2, 1, 0, List.of("Odd"), List.of()));
    RecordedRequest request = backend.expect("POST", "/api/v1/hangar/import/ships");
    assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
    assertThat(request.getBody().readString(StandardCharsets.UTF_8))
        .contains("name=\"file\"")
        .contains("filename=\"shiplist.json\"")
        .contains("{\"ships\":[]}");
  }

  /**
   * An upload with a filename, as the import proxy streams it.
   *
   * @param filename the announced filename
   * @return the resource
   */
  private static Resource upload(String filename) {
    return new ByteArrayResource("{\"ships\":[]}".getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }
}
