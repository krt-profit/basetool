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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient.MatrixFilter;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient.UexEntity;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCreateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialExternalAliasWriteRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link CatalogueBackendClient} sends (plan F3), each the exact URI and body the
 * catalogue controllers sent before the client existed.
 */
class CatalogueBackendClientTest {

  private static final UUID ID = UUID.fromString("ca7a1090-0000-4000-8000-000000000001");
  private static final UUID OTHER = UUID.fromString("ca7a1090-0000-4000-8000-000000000002");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private CatalogueBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new CatalogueBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void materialsPagesAndTheMatrix() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{}");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(
        "{\"content\":[{\"id\":\""
            + OTHER
            + "\",\"name\":\"Area18 TDD\",\"nickname\":\"TDD\",\"starSystemName\":\"Stanton\","
            + "\"planetName\":\"ArcCorp\",\"cityName\":\"Area18\",\"spaceStationName\":null,"
            + "\"hasLoadingDock\":true,\"isAutoLoad\":false,\"hasLoadingDockOverridden\":false,"
            + "\"isAutoLoadOverridden\":false,\"uexHasLoadingDock\":true,\"uexIsAutoLoad\":false,"
            + "\"uexSyncedAt\":\"2026-10-03T11:30:00Z\",\"hidden\":false}],\"page\":0,\"size\":1,"
            + "\"totalElements\":1,\"totalPages\":1}");
    backend.answerJson(EMPTY_PAGE);

    client.materialPriceOverview();
    client.materialsMatrix();
    client.filteredMatrixPage(
        new MatrixFilter(List.of("Gold", "Agricium"), List.of("Stanton"), true, true), 2);
    client.filteredMatrixPage(new MatrixFilter(null, List.of(), false, false), 0);
    client.material(ID);
    client.materialPricePage(ID, 1);
    assertThat(client.terminalCatalogue().content())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(OTHER);
              assertThat(row.starSystemName()).isEqualTo("Stanton");
              assertThat(row.uexSyncedAt()).isEqualTo(Instant.parse("2026-10-03T11:30:00Z"));
            });
    client.shipTypesSorted();

    backend.expect("GET", "/api/v1/materials/prices-overview?size=10000&sort=name,asc");
    backend.expect("GET", "/api/v1/materials/matrix?size=100000&page=0");
    backend.expect(
        "GET",
        "/api/v1/materials/matrix?size=100000&materialNames=Gold&materialNames=Agricium"
            + "&starSystems=Stanton&hasLoadingDock=true&isAutoLoad=true&page=2");
    backend.expect("GET", "/api/v1/materials/matrix?size=100000&page=0");
    backend.expect("GET", "/api/v1/materials/" + ID);
    backend.expect(
        "GET", "/api/v1/materials/" + ID + "/prices?size=10000&sort=terminal.name,asc&page=1");
    backend.expect("GET", "/api/v1/terminals?size=10000&page=0");
    backend.expect("GET", "/api/v1/ship-types?size=1000&sort=name,asc&page=0");
  }

  @Test
  void proxyRelaysAndPickerSearches() {
    backend.answerJson("[{\"terminalId\":\"" + OTHER + "\",\"terminalName\":\"TDD\"}]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.materialSellingTerminals(ID))
        .singleElement()
        .satisfies(t -> assertThat(t.terminalName()).isEqualTo("TDD"));
    client.profitCalculation(ID, List.of("Stanton", "Pyro"));
    client.profitCalculation(ID, null);
    client.searchMaterials("lara", true, false, 26);
    client.searchLocations("", 101);

    backend.expect("GET", "/api/v1/materials/" + ID + "/terminals");
    backend.expect(
        "GET",
        "/api/v1/materials/profit-calculation?shipId="
            + ID
            + "&starSystemNames=Stanton&starSystemNames=Pyro");
    backend.expect("GET", "/api/v1/materials/profit-calculation?shipId=" + ID);
    backend.expect(
        "GET",
        "/api/v1/materials/search?search=lara&jobOrderOnly=true&rawOnly=false&size=26"
            + "&sort=name,asc");
    backend.expect("GET", "/api/v1/locations/search?search=&size=101&sort=name,asc");
  }

  @Test
  void adminMaterialsAndCategories() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();

    client.materialPage(3);
    client.createMaterial(
        new MaterialCreateAjaxRequest(
            "Gold", "COMMODITY", "SCU", null, null, null, null, null, null, null, null));
    client.updateMaterial(
        ID,
        new MaterialDto(
            ID, "Gold", null, "SCU", null, null, null, null, null, null, null, null, null, null,
            4L));
    client.materialCategories();
    client.materialCategory(OTHER);
    client.createMaterialCategory(new MaterialCategoryDto(null, "Ores", null));
    client.deleteMaterialCategory(OTHER);

    backend.expect("GET", "/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page=3");
    assertThat(bodyOf(backend.expect("POST", "/api/v1/materials")))
        .contains("\"name\":\"Gold\"", "\"quantityType\":\"SCU\"");
    assertThat(bodyOf(backend.expect("PUT", "/api/v1/materials/" + ID)))
        .contains("\"name\":\"Gold\"", "\"version\":4");
    backend.expect("GET", "/api/v1/material-categories");
    backend.expect("GET", "/api/v1/material-categories/" + OTHER);
    backend.expect(
        "POST", "/api/v1/material-categories", "{\"id\":null,\"name\":\"Ores\",\"version\":null}");
    backend.expect("DELETE", "/api/v1/material-categories/" + OTHER, null);
  }

  @Test
  void materialAliases() {
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();
    MaterialExternalAliasWriteRequest body =
        new MaterialExternalAliasWriteRequest(ID, "UEX", "Gold", null, null, "GOLD", null, 2L);

    client.materialAliases();
    client.materialAlias(OTHER);
    client.createMaterialAlias(body);
    client.updateMaterialAlias(OTHER, body);
    client.deleteMaterialAlias(OTHER);

    String json =
        "{\"materialId\":\""
            + ID
            + "\",\"sourceSystem\":\"UEX\",\"externalName\":\"Gold\",\"externalKey\":null,"
            + "\"externalUuid\":null,\"externalCode\":\"GOLD\",\"note\":null,\"version\":2}";
    backend.expect("GET", "/api/v1/material-external-aliases");
    backend.expect("GET", "/api/v1/material-external-aliases/" + OTHER);
    backend.expect("POST", "/api/v1/material-external-aliases", json);
    backend.expect("PUT", "/api/v1/material-external-aliases/" + OTHER, json);
    backend.expect("DELETE", "/api/v1/material-external-aliases/" + OTHER, null);
  }

  @Test
  void locations() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{}");
    backend.answerEmpty();

    client.locationPage(0);
    client.location(ID);
    client.updateLocation(ID, new LocationDto(ID, "Area18", "ArcCorp city", true, false, 3L));

    backend.expect("GET", "/api/v1/locations?size=1000&sort=name,asc&includeHidden=true&page=0");
    backend.expect("GET", "/api/v1/locations/" + ID);
    backend.expect(
        "PUT",
        "/api/v1/locations/" + ID,
        "{\"id\":\""
            + ID
            + "\",\"name\":\"Area18\",\"description\":\"ArcCorp city\",\"hidden\":true,"
            + "\"homeLocation\":false,\"version\":3}");
  }

  @Test
  void uexUniverseReadsAndTerminalWrite() {
    for (int i = 0; i < 5; i++) {
      backend.answerJson(EMPTY_PAGE);
    }
    backend.answerJson("{}");
    backend.answerEmpty();

    client.cityPage(0);
    client.spaceStationPage(1);
    client.outpostPage(0);
    client.poiPage(0);
    client.terminalPage(2);
    client.terminal(ID);
    client.updateTerminal(
        ID,
        new TerminalDto(
            ID, "TDD", null, "Stanton", null, null, null, null, null, false, false, null, null,
            null, true));

    backend.expect("GET", "/api/v1/cities?size=10000&sort=name,asc&page=0");
    backend.expect("GET", "/api/v1/space-stations?size=10000&sort=name,asc&page=1");
    backend.expect("GET", "/api/v1/outposts?size=10000&sort=name,asc&page=0");
    backend.expect("GET", "/api/v1/pois?size=10000&sort=name,asc&page=0");
    backend.expect("GET", "/api/v1/terminals?size=10000&sort=name,asc&page=2");
    backend.expect("GET", "/api/v1/terminals/" + ID);
    assertThat(bodyOf(backend.expect("PUT", "/api/v1/terminals/" + ID)))
        .contains("\"name\":\"TDD\"", "\"hidden\":true");
  }

  @Test
  void uexOverridesHitTheLiteralPathOfEachEntityKind() {
    for (int i = 0; i < UexEntity.values().length * 3 + 3; i++) {
      backend.answerEmpty();
    }

    for (UexEntity entity : UexEntity.values()) {
      client.pinLoadingDock(entity, ID, true);
      client.pinLoadingDock(entity, ID, false);
      client.clearLoadingDockPin(entity, ID);
    }
    client.pinTerminalAutoLoad(ID, true);
    client.pinTerminalAutoLoad(ID, false);
    client.clearTerminalAutoLoadPin(ID);

    for (String collection : List.of("cities", "space-stations", "outposts", "pois", "terminals")) {
      String item = "/api/v1/" + collection + "/" + ID;
      backend.expect("PATCH", item + "/loading-dock?value=true", null);
      backend.expect("PATCH", item + "/loading-dock?value=false", null);
      backend.expect("DELETE", item + "/loading-dock-override", null);
    }
    backend.expect("PATCH", "/api/v1/terminals/" + ID + "/auto-load?value=true", null);
    backend.expect("PATCH", "/api/v1/terminals/" + ID + "/auto-load?value=false", null);
    backend.expect("DELETE", "/api/v1/terminals/" + ID + "/auto-load-override", null);
  }

  @Test
  void p4kImportJobs() {
    backend.answerJson("{}");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");

    client.enqueueP4kImport("{\"items\":[]}".getBytes(StandardCharsets.UTF_8), "catalog.json");
    assertThat(client.p4kImportJobs()).isEmpty();
    client.p4kImportJob(ID);
    client.applyP4kImportJob(ID, true);

    RecordedRequest upload = backend.expect("POST", "/api/v1/admin/import/p4k/jobs");
    assertThat(upload.getHeader("Content-Type")).startsWith("multipart/form-data");
    assertThat(bodyOf(upload))
        .contains("name=\"file\"", "filename=\"catalog.json\"", "{\"items\":[]}");
    backend.expect("GET", "/api/v1/admin/import/p4k/jobs");
    backend.expect("GET", "/api/v1/admin/import/p4k/jobs/" + ID);
    backend.expect("POST", "/api/v1/admin/import/p4k/jobs/" + ID + "/apply?seedNew=true");
  }

  @Test
  void syncReports() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{\"deleted\":3}");
    backend.answerJson("{\"deleted\":0}");

    client.syncReportPage(2, "UEX");
    client.syncReportPage(0, null);
    assertThat(client.purgeSyncReports("SCWIKI", 30).deleted()).isEqualTo(3);
    client.purgeSyncReports(null, 7);

    backend.expect("GET", "/api/v1/sync-reports?page=2&size=100&source=UEX");
    backend.expect("GET", "/api/v1/sync-reports?page=0&size=100");
    backend.expect("DELETE", "/api/v1/sync-reports?olderThanDays=30&source=SCWIKI", null);
    backend.expect("DELETE", "/api/v1/sync-reports?olderThanDays=7", null);
  }

  @Test
  void missionReferenceData() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    for (int i = 0; i < 13; i++) {
      backend.answerEmpty();
    }
    JobTypeDto jobType =
        new JobTypeDto(null, "Miner", "d", "MISSION", null, true, false, false, 0L);
    SquadronDto squadron = new SquadronDto(null, "Alpha", "A", "d", true, true, false, 0L);
    FrequencyTypeDto created = new FrequencyTypeDto(null, "Comms", "d", true, null, null);
    FrequencyTypeDto updated = new FrequencyTypeDto(null, "Comms", "d", false, null, 6L);

    client.jobTypePage(true, 0);
    client.squadronPage(false, 1);
    client.frequencyTypePage(false, 0);
    client.frequencyTypePage(true, 0);
    client.createJobType(jobType);
    client.updateJobType(ID, jobType);
    client.deleteJobType(ID);
    client.activateJobType(ID);
    client.createSquadron(squadron);
    client.updateSquadron(ID, squadron);
    client.deleteSquadron(ID);
    client.activateSquadron(ID);
    client.createFrequencyType(created);
    client.updateFrequencyType(ID, updated);
    client.deleteFrequencyType(ID);
    client.activateFrequencyType(ID);
    client.reorderFrequencyTypes(List.of(ID, OTHER));

    backend.expect("GET", "/api/v1/job-types?size=1000&sort=name,asc&includeInactive=true&page=0");
    backend.expect("GET", "/api/v1/squadrons?size=1000&sort=name,asc&includeInactive=false&page=1");
    backend.expect(
        "GET", "/api/v1/frequency-types?size=1000&sort=sortIndex,asc&active=true&page=0");
    backend.expect("GET", "/api/v1/frequency-types?size=1000&sort=sortIndex,asc&page=0");
    assertThat(bodyOf(backend.expect("POST", "/api/v1/job-types")))
        .contains("\"name\":\"Miner\"", "\"archetype\":\"MISSION\"");
    assertThat(bodyOf(backend.expect("PUT", "/api/v1/job-types/" + ID)))
        .contains("\"name\":\"Miner\"");
    backend.expect("DELETE", "/api/v1/job-types/" + ID, null);
    backend.expect("POST", "/api/v1/job-types/" + ID + "/activate", null);
    assertThat(bodyOf(backend.expect("POST", "/api/v1/squadrons")))
        .contains("\"name\":\"Alpha\"", "\"shorthand\":\"A\"");
    assertThat(bodyOf(backend.expect("PUT", "/api/v1/squadrons/" + ID)))
        .contains("\"name\":\"Alpha\"");
    backend.expect("DELETE", "/api/v1/squadrons/" + ID, null);
    backend.expect("POST", "/api/v1/squadrons/" + ID + "/activate", null);
    backend.expect(
        "POST",
        "/api/v1/frequency-types",
        "{\"id\":null,\"name\":\"Comms\",\"description\":\"d\",\"active\":true,"
            + "\"sortIndex\":null,\"version\":null}");
    backend.expect(
        "PUT",
        "/api/v1/frequency-types/" + ID,
        "{\"id\":null,\"name\":\"Comms\",\"description\":\"d\",\"active\":false,"
            + "\"sortIndex\":null,\"version\":6}");
    backend.expect("DELETE", "/api/v1/frequency-types/" + ID, null);
    backend.expect("POST", "/api/v1/frequency-types/" + ID + "/activate", null);
    backend.expect("POST", "/api/v1/frequency-types/reorder", "[\"" + ID + "\",\"" + OTHER + "\"]");
  }

  @Test
  void shipData() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();

    client.manufacturerPage(0);
    client.shipTypePage(1);
    client.setManufacturerHidden(ID, true);
    client.setShipTypeHidden(OTHER, false);
    client.resetAllFitted();

    backend.expect(
        "GET", "/api/v1/manufacturers?size=1000&sort=name,asc&includeHidden=true&page=0");
    backend.expect("GET", "/api/v1/ship-types?size=1000&sort=name,asc&includeHidden=true&page=1");
    backend.expect("PUT", "/api/v1/manufacturers/" + ID + "/visibility?hidden=true", null);
    backend.expect("PUT", "/api/v1/ship-types/" + OTHER + "/visibility?hidden=false", null);
    backend.expect("POST", "/api/v1/hangar/ships/reset-fitted", null);
  }

  @Test
  void qualityTiers() {
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();
    QualityTierWriteDto body = new QualityTierWriteDto("A", 900, "Hoch", "High", 1, true, null);

    client.qualityTiers();
    client.createQualityTier(body);
    client.updateQualityTier(ID, body);
    client.deleteQualityTier(ID);

    String json =
        "{\"code\":\"A\",\"minQuality\":900,\"labelDe\":\"Hoch\",\"labelEn\":\"High\","
            + "\"sortOrder\":1,\"active\":true,\"version\":null}";
    backend.expect("GET", "/api/v1/admin/quality-tiers");
    backend.expect("POST", "/api/v1/admin/quality-tiers", json);
    backend.expect("PUT", "/api/v1/admin/quality-tiers/" + ID, json);
    backend.expect("DELETE", "/api/v1/admin/quality-tiers/" + ID, null);
  }

  /**
   * Reads a recorded request's body as UTF-8 text.
   *
   * @param request the recorded request
   * @return the body text
   */
  private static String bodyOf(RecordedRequest request) {
    return request.getBody().readString(StandardCharsets.UTF_8);
  }
}
