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

package de.greluc.krt.profit.basetool.frontend.blueprint.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link BlueprintBackendClient} sends (plan F3), each the exact request the
 * blueprint controllers sent before the client existed.
 */
class BlueprintBackendClientTest {

  private static final UUID ID = UUID.fromString("0b6f1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d");
  private static final UUID SUB = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";
  private static final String EMPTY_PREVIEW =
      "{\"total\":0,\"matched\":0,\"matchedByAlias\":0,\"suggested\":0,\"unmatched\":0,"
          + "\"alreadyOwned\":0,\"entries\":[]}";

  private BackendClientHarness backend;
  private BlueprintBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new BlueprintBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void ownedBlueprintReads() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerEmpty();
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.inventoryItemCountPage()).isNotNull();
    assertThat(client.searchProducts("Aurora MR", 25)).isEmpty();
    assertThat(client.searchProducts("", 200)).isEmpty();
    client.recipe(ID);
    assertThat(client.craftability(true)).isEmpty();
    assertThat(client.ownedPage("Gladius", 500, 2)).isNotNull();
    assertThat(client.ownedPage(" ", 500, 0)).isNotNull();

    backend.expect("GET", "/api/v1/personal-inventory?size=1");
    backend.expect("GET", "/api/v1/blueprints/products/search?q=Aurora%20MR&limit=25");
    backend.expect("GET", "/api/v1/blueprints/products/search?q=&limit=200");
    backend.expect("GET", "/api/v1/personal-blueprints/" + ID + "/recipe");
    backend.expect("GET", "/api/v1/personal-blueprints/craftability?includeRefinery=true");
    backend.expect(
        "GET", "/api/v1/personal-blueprints?size=500&page=2&sort=productName,asc&q=Gladius");
    backend.expect("GET", "/api/v1/personal-blueprints?size=500&page=0&sort=productName,asc");
  }

  @Test
  void ownedBlueprintWrites() {
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    client.addOwned(new PersonalBlueprintBatchCreateRequest(List.of("aurora_mr", "gladius")));
    client.updateOwned(ID, new PersonalBlueprintUpdateRequest(null, "Aus dem Event", 3L));
    client.deleteOwned(ID);
    client.deleteAllOwned();
    client.importApply(
        new BlueprintImportApplyRequest(
            List.of(new BlueprintImportResolutionDto("Aurora MR", "aurora_mr", null, null))));

    backend.expect(
        "POST",
        "/api/v1/personal-blueprints/batch",
        "{\"productKeys\":[\"aurora_mr\",\"gladius\"]}");
    backend.expect(
        "PUT",
        "/api/v1/personal-blueprints/" + ID,
        "{\"acquiredAt\":null,\"note\":\"Aus dem Event\",\"version\":3}");
    backend.expect("DELETE", "/api/v1/personal-blueprints/" + ID, null);
    backend.expect("DELETE", "/api/v1/personal-blueprints", null);
    backend.expect(
        "POST",
        "/api/v1/personal-blueprints/import/apply",
        "{\"resolutions\":[{\"externalName\":\"Aurora MR\",\"productKey\":\"aurora_mr\","
            + "\"acquiredAt\":null,\"note\":null}]}");
  }

  @Test
  void importPreviewsUploadOneFilePart() {
    backend.answerJson(EMPTY_PREVIEW);
    backend.answerJson(EMPTY_PREVIEW);
    byte[] export = "{\"blueprints\":[]}".getBytes(StandardCharsets.UTF_8);

    assertThat(client.importPreview("scmdb.json", export)).isNotNull();
    assertThat(client.memberImportPreview(SUB, "bp.json", export)).isNotNull();

    RecordedRequest own = backend.expect("POST", "/api/v1/personal-blueprints/import/preview");
    assertFilePart(own, "scmdb.json");
    RecordedRequest member =
        backend.expect("POST", "/api/v1/personal-blueprints/admin/" + SUB + "/import/preview");
    assertFilePart(member, "bp.json");
  }

  @Test
  void overviewAndCatalogue() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.overviewPage(1, 50, "Aurora MR")).isNotNull();
    assertThat(client.overviewPage(0, 10, null)).isNotNull();
    assertThat(client.overviewOwners("aurora_mr")).isEmpty();
    assertThat(client.blueprintPage(25, 3, "omni")).isNotNull();
    assertThat(client.blueprintPage(25, 0, null)).isNotNull();

    backend.expect("GET", "/api/v1/personal-blueprints/overview?page=1&size=50&search=Aurora%20MR");
    backend.expect("GET", "/api/v1/personal-blueprints/overview?page=0&size=10");
    backend.expect("GET", "/api/v1/personal-blueprints/overview/owners?productKey=aurora_mr");
    backend.expect("GET", "/api/v1/blueprints?size=25&page=3&sort=outputName,asc&search=omni");
    backend.expect("GET", "/api/v1/blueprints?size=25&page=0&sort=outputName,asc");
  }

  @Test
  void defaultBlueprintSet() {
    backend.answerJson("[]");
    backend.answerEmpty();
    backend.answerEmpty();

    assertThat(client.defaults()).isEmpty();
    client.addDefault(new DefaultBlueprintCreateRequest("aurora_mr"));
    client.removeDefault(ID);

    backend.expect("GET", "/api/v1/blueprints/admin/defaults");
    backend.expect("POST", "/api/v1/blueprints/admin/defaults", "{\"productKey\":\"aurora_mr\"}");
    backend.expect("DELETE", "/api/v1/blueprints/admin/defaults/" + ID, null);
  }

  @Test
  void memberBlueprintsForTheAdminPage() {
    backend.answerEmpty();
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    client.user(SUB);
    assertThat(client.memberOwnedPage(SUB, 200, "Gladius")).isNotNull();
    assertThat(client.memberOwnedPage(SUB, 200, null)).isNotNull();
    client.addMemberOwned(SUB, new PersonalBlueprintBatchCreateRequest(List.of("gladius")));
    client.updateMemberOwned(ID, new PersonalBlueprintUpdateRequest(null, null, 0L));
    client.deleteMemberOwned(ID);
    client.memberImportApply(SUB, new BlueprintImportApplyRequest(List.of()));
    client.deleteAllMembersOwned();

    backend.expect("GET", "/api/v1/users/" + SUB);
    backend.expect(
        "GET",
        "/api/v1/personal-blueprints/admin/" + SUB + "?size=200&sort=productName,asc&q=Gladius");
    backend.expect(
        "GET", "/api/v1/personal-blueprints/admin/" + SUB + "?size=200&sort=productName,asc");
    backend.expect(
        "POST",
        "/api/v1/personal-blueprints/admin/" + SUB + "/batch",
        "{\"productKeys\":[\"gladius\"]}");
    backend.expect(
        "PUT",
        "/api/v1/personal-blueprints/admin/items/" + ID,
        "{\"acquiredAt\":null,\"note\":null,\"version\":0}");
    backend.expect("DELETE", "/api/v1/personal-blueprints/admin/items/" + ID, null);
    backend.expect(
        "POST",
        "/api/v1/personal-blueprints/admin/" + SUB + "/import/apply",
        "{\"resolutions\":[]}");
    backend.expect("DELETE", "/api/v1/personal-blueprints/admin", null);
  }

  /**
   * Checks that a request is a multipart upload carrying one octet-stream {@code file} part.
   *
   * @param request the recorded request
   * @param filename the expected part file name
   */
  private static void assertFilePart(RecordedRequest request, String filename) {
    assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
    String body = request.getBody().readString(StandardCharsets.UTF_8);
    assertThat(body)
        .contains("name=\"file\"")
        .contains("filename=\"" + filename + "\"")
        .contains("Content-Type: application/json")
        .contains("{\"blueprints\":[]}");
  }
}
