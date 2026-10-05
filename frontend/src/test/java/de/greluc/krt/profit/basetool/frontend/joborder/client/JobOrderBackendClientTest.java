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

package de.greluc.krt.profit.basetool.frontend.joborder.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.AssigneeNoteRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderItemLineDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderItemRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateJobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.HandoverReportItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderHandoverItemCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverEntryCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateJobOrderBlueprintCountingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateJobOrderStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link JobOrderBackendClient} sends (plan F3), each the exact verb, URI and
 * body the job-order controllers sent before the client existed.
 */
class JobOrderBackendClientTest {

  private static final UUID ORDER = UUID.fromString("0f468278-136b-4988-a223-92692f34ca89");
  private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID SQUADRON = UUID.fromString("6a1c0e2b-7d3f-4b8a-9c51-2e4f8a0b1c2d");
  private static final UUID MATERIAL = UUID.fromString("9b7e3f10-24c8-4d6a-8f12-3c5e7a9b0d14");
  private static final Instant TIME = Instant.parse("2026-09-01T10:15:30Z");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private JobOrderBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new JobOrderBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void orderListCarriesScopeStatusesAndSquadrons() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    client.orders(false, false, 2, 100, List.of("OPEN", "IN_PROGRESS"), List.of(SQUADRON, OTHER));
    client.orders(true, false, 0, 50, List.of("COMPLETED"), List.of(SQUADRON));
    client.orders(false, true, 1, 200, List.of("OPEN"), List.of(SQUADRON));

    backend.expect(
        "GET",
        "/api/v1/orders?page=2&size=100&sort=priority,asc&status=OPEN,IN_PROGRESS&squadronId="
            + SQUADRON
            + "&squadronId="
            + OTHER);
    backend.expect(
        "GET", "/api/v1/orders/requested?page=0&size=50&sort=priority,asc&status=COMPLETED");
    backend.expect(
        "GET", "/api/v1/orders?page=1&size=200&sort=priority,asc&status=OPEN&toProcess=true");
  }

  @Test
  void orderReads() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("{}");
    backend.answerJson("[]");
    backend.answerJson("{\"groups\":[]}");

    assertThat(client.order(ORDER)).isNotNull();
    assertThat(client.itemBlueprintOwners(ORDER)).isNotNull();
    assertThat(client.itemStock(ORDER)).isEmpty();
    assertThat(client.orphanedInventory(ORDER)).isEmpty();
    assertThat(client.itemBlueprints(OTHER)).isEmpty();
    assertThat(client.itemDerivation(OTHER, 3)).isNotNull();
    assertThat(client.searchOrderableItems("cirrus", 20)).isNotNull();
    assertThat(client.inventoryForMaterial(ORDER, MATERIAL)).isEmpty();
    assertThat(client.stockAttribution(ORDER, MATERIAL)).isEmpty();
    assertThat(client.currentUser()).isNotNull();
    assertThat(client.materialCollection(ORDER)).isEmpty();
    assertThat(client.materialDemand()).isNotNull();

    backend.expect("GET", "/api/v1/orders/" + ORDER);
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/item-blueprint-owners");
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/item-stock");
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/inventory/orphaned");
    backend.expect("GET", "/api/v1/orders/item-catalog/" + OTHER + "/blueprints");
    backend.expect(
        "GET", "/api/v1/orders/item-catalog/blueprints/" + OTHER + "/derivation?amount=3");
    backend.expect("GET", "/api/v1/orders/item-catalog?search=cirrus&size=20&sort=name,asc");
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/materials/" + MATERIAL + "/inventory");
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/materials/" + MATERIAL + "/attribution");
    backend.expect("GET", "/api/v1/users/me");
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/material-collection");
    backend.expect("GET", "/api/v1/orders/material-demand");
  }

  @Test
  void cachedCatalogues() {
    backend.answerJson("{\"id\":\"job_order.age_yellow_days\",\"value\":\"30\",\"version\":1}");
    backend.answerJson("{\"id\":\"job_order.age_red_days\",\"value\":\"90\",\"version\":1}");
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");

    assertThat(client.ageYellowSetting().value()).isEqualTo("30");
    assertThat(client.ageRedSetting().value()).isEqualTo("90");
    assertThat(client.jobOrderMaterials()).isEmpty();
    assertThat(client.orderableItemProbe()).isNotNull();
    assertThat(client.squadrons()).isNotNull();
    assertThat(client.activeOrgUnits()).isEmpty();
    assertThat(client.activeOrgUnitsAllKinds()).isEmpty();
    assertThat(client.locations()).isEmpty();

    backend.expect("GET", "/api/v1/settings/job_order.age_yellow_days");
    backend.expect("GET", "/api/v1/settings/job_order.age_red_days");
    backend.expect("GET", "/api/v1/materials/job-order");
    backend.expect("GET", "/api/v1/orders/item-catalog?size=1&sort=name,asc");
    backend.expect("GET", "/api/v1/squadrons?size=1000&sort=name,asc&page=0");
    backend.expect("GET", "/api/v1/org-units/active");
    backend.expect("GET", "/api/v1/org-units/active-all-kinds");
    backend.expect("GET", "/api/v1/locations/lookup");
  }

  @Test
  void orderWrites() {
    for (int i = 0; i < 8; i++) {
      backend.answerJson("{}");
    }
    backend.answerEmpty();
    CreateJobOrderItemRequestDto itemOrder =
        new CreateJobOrderItemRequestDto(
            SQUADRON,
            OTHER,
            "Pilot",
            "Bitte bis Freitag",
            List.of(
                new CreateJobOrderItemLineDto(
                    null,
                    MATERIAL,
                    ORDER,
                    2,
                    List.of(new CreateJobOrderItemMaterialDto(MATERIAL, "B")),
                    0,
                    null)),
            null);
    CreateJobOrderDto materialOrder =
        new CreateJobOrderDto(
            null,
            OTHER,
            "Pilot",
            "Eilig",
            List.of(new CreateJobOrderMaterialDto(MATERIAL, 500, 12.5)),
            3L);

    client.createItemOrder(itemOrder);
    client.updateItemOrder(ORDER, itemOrder);
    client.createOrder(materialOrder);
    client.updateOrder(ORDER, materialOrder);
    client.updateOrderAsRequester(ORDER, materialOrder);
    client.updatePriority(ORDER, 4);
    client.updateStatus(ORDER, new UpdateJobOrderStatusDto("IN_PROGRESS", 3L));
    client.updateBlueprintVariantCounting(ORDER, new UpdateJobOrderBlueprintCountingDto(true, 3L));
    client.deleteOrder(ORDER);

    String itemBody =
        "{\"responsibleOrgUnitId\":\""
            + SQUADRON
            + "\",\"requestingOrgUnitId\":\""
            + OTHER
            + "\",\"handle\":\"Pilot\",\"comment\":\"Bitte bis Freitag\",\"items\":[{\"id\":null,"
            + "\"gameItemId\":\""
            + MATERIAL
            + "\",\"blueprintId\":\""
            + ORDER
            + "\",\"amount\":2,\"materials\":[{\"materialId\":\""
            + MATERIAL
            + "\",\"quality\":\"B\"}],\"clientLineId\":0,\"parentClientLineId\":null}],"
            + "\"version\":null}";
    String materialBody =
        "{\"responsibleOrgUnitId\":null,\"requestingOrgUnitId\":\""
            + OTHER
            + "\",\"handle\":\"Pilot\",\"comment\":\"Eilig\",\"materials\":[{\"materialId\":\""
            + MATERIAL
            + "\",\"minQuality\":500,\"amount\":12.5}],\"version\":3}";
    backend.expect("POST", "/api/v1/orders/items", itemBody);
    backend.expect("PUT", "/api/v1/orders/" + ORDER + "/items", itemBody);
    backend.expect("POST", "/api/v1/orders", materialBody);
    backend.expect("PUT", "/api/v1/orders/" + ORDER, materialBody);
    backend.expect("PUT", "/api/v1/orders/" + ORDER + "/requested", materialBody);
    backend.expect("PUT", "/api/v1/orders/" + ORDER + "/priority?priority=4", null);
    backend.expect(
        "PUT", "/api/v1/orders/" + ORDER + "/status", "{\"status\":\"IN_PROGRESS\",\"version\":3}");
    backend.expect(
        "PATCH",
        "/api/v1/orders/" + ORDER + "/blueprint-variant-counting",
        "{\"countBlueprintsWithVariants\":true,\"version\":3}");
    backend.expect("DELETE", "/api/v1/orders/" + ORDER, null);
  }

  @Test
  void claimsAndAssignees() {
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");

    client.upsertClaim(ORDER, new CreateClaimDto(MATERIAL, "B", SQUADRON, 4.0));
    client.withdrawClaim(ORDER, OTHER);
    client.addAssignee(ORDER, OTHER);
    client.removeAssignee(ORDER, OTHER);
    client.setAssigneeNote(ORDER, OTHER, new AssigneeNoteRequest("Abends ab 20 Uhr", 4L));
    client.deleteAssigneeNote(ORDER, OTHER, 7L);
    client.deleteAssigneeNote(ORDER, OTHER, null);

    backend.expect(
        "POST",
        "/api/v1/orders/" + ORDER + "/claims",
        "{\"materialId\":\""
            + MATERIAL
            + "\",\"qualityRequirement\":\"B\",\"claimingOrgUnitId\":\""
            + SQUADRON
            + "\",\"amount\":4.0}");
    backend.expect("DELETE", "/api/v1/orders/" + ORDER + "/claims/" + OTHER, null);
    backend.expect("POST", "/api/v1/orders/" + ORDER + "/assignees/" + OTHER, null);
    backend.expect("DELETE", "/api/v1/orders/" + ORDER + "/assignees/" + OTHER, null);
    backend.expect(
        "PUT",
        "/api/v1/orders/" + ORDER + "/assignees/" + OTHER + "/note",
        "{\"note\":\"Abends ab 20 Uhr\",\"version\":4}");
    backend.expect(
        "DELETE", "/api/v1/orders/" + ORDER + "/assignees/" + OTHER + "/note?version=7", null);
    backend.expect("DELETE", "/api/v1/orders/" + ORDER + "/assignees/" + OTHER + "/note", null);
  }

  @Test
  void handoversProductionAndUnlinks() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerEmpty();

    client.createHandover(
        ORDER,
        new JobOrderHandoverCreateDto(
            TIME,
            "Pilot",
            "IRI",
            List.of(new JobOrderHandoverItemCreateDto(MATERIAL, 12.5, null, "B"))));
    client.createItemHandover(
        ORDER,
        new JobOrderItemHandoverCreateDto(
            TIME, "Pilot", List.of(new JobOrderItemHandoverEntryCreateDto(OTHER, 2))));
    client.bookProduction(
        ORDER, OTHER, new JobOrderItemProductionCreateDto(3, 2L, List.of(), List.of(), null));
    client.unlinkMaterial(ORDER, MATERIAL);
    client.unlinkInventoryItem(ORDER, OTHER);

    backend.expect(
        "POST",
        "/api/v1/orders/" + ORDER + "/handovers",
        "{\"handoverTime\":\"2026-09-01T10:15:30Z\",\"recipientHandle\":\"Pilot\","
            + "\"recipientSquadron\":\"IRI\",\"items\":[{\"inventoryItemId\":\""
            + MATERIAL
            + "\",\"amount\":12.5,\"missionReductions\":null,\"qualityRequirement\":\"B\"}]}");
    backend.expect(
        "POST",
        "/api/v1/orders/" + ORDER + "/item-handovers",
        "{\"handoverTime\":\"2026-09-01T10:15:30Z\",\"recipientHandle\":\"Pilot\","
            + "\"entries\":[{\"jobOrderItemId\":\""
            + OTHER
            + "\",\"amount\":2}]}");
    backend.expect(
        "POST",
        "/api/v1/orders/" + ORDER + "/items/" + OTHER + "/production",
        "{\"amount\":3,\"version\":2,\"consumption\":[],\"skippedMaterialIds\":[],"
            + "\"bookIn\":null}");
    backend.expect("DELETE", "/api/v1/orders/" + ORDER + "/materials/" + MATERIAL, null);
    backend.expect("DELETE", "/api/v1/orders/" + ORDER + "/inventory/" + OTHER + "/unlink", null);
  }

  @Test
  void handoverReports() {
    byte[] pdf = {0x25, 0x50, 0x44, 0x46};
    backend.answerBytes("application/pdf", pdf);
    backend.answerBytes("application/pdf", pdf);
    backend.answerBytes("application/pdf", pdf);
    HandoverReportPreviewRequestDto preview =
        new HandoverReportPreviewRequestDto(
            "42",
            LocalDateTime.parse("2026-09-01T10:15:30"),
            "Pilot",
            List.of(new HandoverReportItemDto("Laranite", "Port Olisar", 12.5, 500, "SCU")));

    assertThat(client.handoverReport(ORDER, OTHER, "Europe/Berlin")).isEqualTo(pdf);
    assertThat(client.itemHandoverReport(ORDER, OTHER, " ")).isEqualTo(pdf);
    assertThat(client.handoverReportPreview(ORDER, preview)).isEqualTo(pdf);

    RecordedRequest material =
        backend.expect("GET", "/api/v1/orders/" + ORDER + "/handovers/" + OTHER + "/report");
    assertThat(material.getHeader("X-User-Time-Zone")).isEqualTo("Europe/Berlin");
    RecordedRequest item =
        backend.expect("GET", "/api/v1/orders/" + ORDER + "/item-handovers/" + OTHER + "/report");
    assertThat(item.getHeader("X-User-Time-Zone")).isNull();
    backend.expect(
        "POST",
        "/api/v1/orders/" + ORDER + "/handovers/report/preview",
        "{\"jobOrderNumber\":\"42\",\"handoverTime\":\"2026-09-01T10:15:30\","
            + "\"recipientHandle\":\"Pilot\",\"items\":[{\"materialName\":\"Laranite\","
            + "\"locationName\":\"Port Olisar\",\"amount\":12.5,\"quality\":500,"
            + "\"quantityType\":\"SCU\"}]}");
  }
}
