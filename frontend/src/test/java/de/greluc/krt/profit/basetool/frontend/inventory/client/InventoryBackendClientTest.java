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

package de.greluc.krt.profit.basetool.frontend.inventory.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.inventory.model.BulkCheckoutRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.BulkRebookMode;
import de.greluc.krt.profit.basetool.frontend.inventory.model.BulkRebookRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemCreateDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemNoteUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.UpdateDeliveredRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Pins the requests {@link InventoryBackendClient} sends (plan F3), each the exact URI and body the
 * inventory controllers built before the client existed.
 */
class InventoryBackendClientTest {

  private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID MATERIAL = UUID.fromString("22222222-3333-4444-5555-666666666666");
  private static final UUID GAME_ITEM = UUID.fromString("33333333-4444-5555-6666-777777777777");
  private static final UUID LOCATION = UUID.fromString("44444444-5555-6666-7777-888888888888");
  private static final UUID JOB_ORDER = UUID.fromString("55555555-6666-7777-8888-999999999999");
  private static final UUID MISSION = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
  private static final UUID USER = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");
  private static final UUID ORG_UNIT = UUID.fromString("88888888-9999-aaaa-bbbb-cccccccccccc");
  private static final UUID OTHER = UUID.fromString("99999999-aaaa-bbbb-cccc-dddddddddddd");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private InventoryBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new InventoryBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void aggregatedDrilldownsAndItemSearch() {
    for (int i = 0; i < 5; i++) {
      backend.answerJson(EMPTY_PAGE);
    }

    client.aggregated(2, 50, false);
    client.aggregated(null, null, true);
    client.drilldownPage(false, MATERIAL, 0, 50);
    client.drilldownPage(true, GAME_ITEM, 3, 100);
    client.itemCatalog(26, "Gold Erz");

    backend.expect(
        "GET",
        "/api/v1/inventory/aggregated?page=2&size=50"
            + "&sort=material.name,asc;quality,desc;amount,desc");
    backend.expect("GET", "/api/v1/inventory/aggregated?catalog=ITEM");
    backend.expect("GET", "/api/v1/inventory/material/" + MATERIAL + "?page=0&size=50");
    backend.expect("GET", "/api/v1/inventory/game-item/" + GAME_ITEM + "?page=3&size=100");
    backend.expect("GET", "/api/v1/inventory/item-catalog?size=26&sort=name,asc&q=Gold%20Erz");
  }

  @Test
  void groupedListingsCarryEveryPresentFilter() {
    for (int i = 0; i < 4; i++) {
      backend.answerJson("[]");
    }

    client.groupedItems(
        InventoryBackendClient.Listing.MY,
        new InventoryBackendClient.ItemFilter(
            List.of(GAME_ITEM), List.of(LOCATION), List.of(JOB_ORDER), true, false, true, false));
    client.groupedItems(InventoryBackendClient.Listing.ALL, InventoryBackendClient.ItemFilter.NONE);
    client.groupedMaterials(
        InventoryBackendClient.Listing.ALL,
        new InventoryBackendClient.MaterialFilter(
            List.of(MATERIAL),
            List.of(LOCATION),
            3,
            List.of(JOB_ORDER),
            List.of(MISSION),
            false,
            true,
            false,
            true));
    client.groupedMaterials(
        InventoryBackendClient.Listing.MY, InventoryBackendClient.MaterialFilter.NONE);

    backend.expect(
        "GET",
        "/api/v1/inventory/my-inventory/grouped?catalog=ITEM&gameItemIds="
            + GAME_ITEM
            + "&locationIds="
            + LOCATION
            + "&jobOrderIds="
            + JOB_ORDER
            + "&personalOnly=true&stolenOnly=true");
    backend.expect("GET", "/api/v1/inventory/all/grouped?catalog=ITEM");
    backend.expect(
        "GET",
        "/api/v1/inventory/all/grouped?materialIds="
            + MATERIAL
            + "&locationIds="
            + LOCATION
            + "&minQuality=3&jobOrderIds="
            + JOB_ORDER
            + "&missionIds="
            + MISSION
            + "&nonPersonalOnly=true&nonStolenOnly=true");
    backend.expect("GET", "/api/v1/inventory/my-inventory/grouped");
  }

  @Test
  void entryIdsKeepTheirOwnParameterOrder() {
    backend.answerJson("[]");
    backend.answerJson("[]");

    assertThat(
            client.myItemEntryIds(
                new InventoryBackendClient.ItemFilter(
                    List.of(GAME_ITEM),
                    List.of(LOCATION),
                    List.of(JOB_ORDER),
                    false,
                    true,
                    false,
                    true)))
        .isEmpty();
    assertThat(
            client.myMaterialEntryIds(
                new InventoryBackendClient.MaterialFilter(
                    List.of(MATERIAL),
                    List.of(LOCATION),
                    2,
                    List.of(JOB_ORDER),
                    List.of(MISSION),
                    true,
                    false,
                    true,
                    false)))
        .isEmpty();

    backend.expect(
        "GET",
        "/api/v1/inventory/my-inventory/entry-ids?catalog=ITEM&gameItemIds="
            + GAME_ITEM
            + "&jobOrderIds="
            + JOB_ORDER
            + "&locationIds="
            + LOCATION
            + "&nonPersonalOnly=true&nonStolenOnly=true");
    backend.expect(
        "GET",
        "/api/v1/inventory/my-inventory/entry-ids?materialIds="
            + MATERIAL
            + "&minQuality=2&jobOrderIds="
            + JOB_ORDER
            + "&missionIds="
            + MISSION
            + "&locationIds="
            + LOCATION
            + "&personalOnly=true&stolenOnly=true");
  }

  @Test
  void stackEntriesAndReleasedIds() {
    for (int i = 0; i < 5; i++) {
      backend.answerJson(EMPTY_PAGE);
    }
    backend.answerJson("[]");

    client.myStackEntries(MATERIAL, LOCATION, 3, true, true, ORG_UNIT, 1, 20);
    client.myStackEntries(MATERIAL, LOCATION, null, false, false, null, null, null);
    client.myGameItemStackEntries(GAME_ITEM, LOCATION, false, true, ORG_UNIT, 0, 10);
    client.allStackEntries(MATERIAL, USER, LOCATION, 2, false, null, null, null);
    client.allGameItemStackEntries(GAME_ITEM, USER, LOCATION, true, ORG_UNIT, 3, 50);
    assertThat(client.releasedItemIds(List.of(ID, OTHER))).isEmpty();

    String mine = "/api/v1/inventory/my-inventory/stack/entries?";
    String all = "/api/v1/inventory/all/stack/entries?";
    backend.expect(
        "GET",
        mine
            + "materialId="
            + MATERIAL
            + "&locationId="
            + LOCATION
            + "&personal=true&stolen=true&quality=3&owningOrgUnitId="
            + ORG_UNIT
            + "&page=1&size=20");
    backend.expect(
        "GET", mine + "materialId=" + MATERIAL + "&locationId=" + LOCATION + "&personal=false");
    backend.expect(
        "GET",
        mine
            + "catalog=ITEM&gameItemId="
            + GAME_ITEM
            + "&locationId="
            + LOCATION
            + "&personal=false&stolen=true&owningOrgUnitId="
            + ORG_UNIT
            + "&page=0&size=10");
    backend.expect(
        "GET",
        all
            + "materialId="
            + MATERIAL
            + "&userId="
            + USER
            + "&locationId="
            + LOCATION
            + "&quality=2");
    backend.expect(
        "GET",
        all
            + "catalog=ITEM&gameItemId="
            + GAME_ITEM
            + "&userId="
            + USER
            + "&locationId="
            + LOCATION
            + "&stolen=true&owningOrgUnitId="
            + ORG_UNIT
            + "&page=3&size=50");
    backend.expect(
        "GET", "/api/v1/material-exchange/released-item-ids?ids=" + ID + "&ids=" + OTHER);
  }

  @Test
  void mergeProbe() {
    backend.answerJson("{\"exists\":true}");
    backend.answerJson("{\"exists\":false}");

    assertThat(client.mergeCandidates(USER, MATERIAL, LOCATION, 3, true, false, ORG_UNIT).exists())
        .isTrue();
    assertThat(client.mergeCandidates(null, MATERIAL, LOCATION, 0, false, true, null).exists())
        .isFalse();

    String probe = "/api/v1/inventory/merge-candidates?materialId=" + MATERIAL;
    backend.expect(
        "GET",
        probe
            + "&locationId="
            + LOCATION
            + "&quality=3&personal=true&stolen=false&userId="
            + USER
            + "&owningOrgUnitId="
            + ORG_UNIT);
    backend.expect(
        "GET", probe + "&locationId=" + LOCATION + "&quality=0&personal=false&stolen=true");
  }

  @Test
  void lookupsAndCatalogues() {
    backend.answerJson("{}");
    for (int i = 0; i < 8; i++) {
      backend.answerJson("[]");
    }

    client.user(USER);
    client.memberships(USER);
    client.pickableOrgUnits();
    client.users();
    client.materials();
    client.locations();
    client.activeJobOrders(true);
    client.activeJobOrders(false);
    client.missions();

    backend.expect("GET", "/api/v1/users/" + USER);
    backend.expect("GET", "/api/v1/users/" + USER + "/memberships?allKinds=true");
    backend.expect("GET", "/api/v1/users/me/pickable-org-units");
    backend.expect("GET", "/api/v1/users/lookup");
    backend.expect("GET", "/api/v1/materials/lookup");
    backend.expect("GET", "/api/v1/locations/lookup");
    backend.expect("GET", "/api/v1/orders/lookup?withNeeds=true");
    backend.expect("GET", "/api/v1/orders/lookup");
    backend.expect("GET", "/api/v1/missions/lookup");
  }

  @Test
  void bookingsAndRebookings() {
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerEmpty();
    backend.answerJson("{}");

    client.create(
        new InventoryItemCreateDto(
            null, MATERIAL, null, LOCATION, 3, 1.5, false, false, null, null, null, true, List.of(),
            List.of()));
    InventoryItemBookOutDto bookOut =
        new InventoryItemBookOutDto(
            2.0, null, LOCATION, null, null, null, 4L, null, null, null, null);
    client.bookOut(ID, bookOut);
    client.transfer(ID, bookOut);
    client.personalRebook(ID, new InventoryItemPersonalRebookDto(1.0, 5L, ORG_UNIT, false));
    client.bulkCheckout(new BulkCheckoutRequest(List.of(ID)));
    client.bulkRebook(
        new BulkRebookRequest(List.of(ID), BulkRebookMode.LOCATION, null, LOCATION, null, null));

    backend.expect(
        "POST",
        "/api/v1/inventory",
        "{\"userId\":null,\"materialId\":\""
            + MATERIAL
            + "\",\"gameItemId\":null,\"locationId\":\""
            + LOCATION
            + "\",\"quality\":3,\"amount\":1.5,\"personal\":false,\"stolen\":false,"
            + "\"missionId\":null,\"jobOrderId\":null,\"owningOrgUnitId\":null,\"mergeStock\":true,"
            + "\"jobOrderAllocations\":[],\"missionAllocations\":[]}");
    String bookOutBody =
        "{\"amount\":2.0,\"targetUserId\":null,\"targetLocationId\":\""
            + LOCATION
            + "\",\"type\":null,\"terminal\":null,\"sellAmount\":null,\"version\":4,"
            + "\"targetOwningOrgUnitId\":null,\"mergeStock\":null,\"jobOrderReductions\":null,"
            + "\"missionReductions\":null}";
    backend.expect("POST", "/api/v1/inventory/" + ID + "/book-out", bookOutBody);
    backend.expect("POST", "/api/v1/inventory/" + ID + "/book-out", bookOutBody);
    backend.expect(
        "POST",
        "/api/v1/inventory/" + ID + "/personal-rebook",
        "{\"amount\":1.0,\"version\":5,\"targetOwningOrgUnitId\":\""
            + ORG_UNIT
            + "\",\"mergeStock\":false}");
    backend.expect("POST", "/api/v1/inventory/bulk-checkout", "{\"itemIds\":[\"" + ID + "\"]}");
    backend.expect(
        "POST",
        "/api/v1/inventory/bulk-rebook",
        "{\"itemIds\":[\""
            + ID
            + "\"],\"mode\":\"LOCATION\",\"targetUserId\":null,\"targetLocationId\":\""
            + LOCATION
            + "\",\"targetOwningOrgUnitId\":null,\"mergeStock\":null}");
  }

  @Test
  void allocationsNotesAndDelivery() {
    for (int i = 0; i < 5; i++) {
      backend.answerJson("{}");
    }
    InventoryAllocationWriteDto allocation =
        new InventoryAllocationWriteDto(InventoryAllocationDimension.JOB_ORDER, JOB_ORDER, 2.5, 7L);

    client.addAllocation(ID, allocation);
    client.changeAllocation(ID, allocation);
    client.removeAllocation(ID, allocation);
    client.updateNote(ID, new InventoryItemNoteUpdateRequest("Notiz", 3L));
    client.updateDelivered(ID, new UpdateDeliveredRequest(true, JOB_ORDER, 2L));

    String allocationBody =
        "{\"field\":\"JOB_ORDER\",\"targetId\":\"" + JOB_ORDER + "\",\"amount\":2.5,\"version\":7}";
    String path = "/api/v1/inventory/" + ID;
    backend.expect("POST", path + "/allocation", allocationBody);
    backend.expect("PATCH", path + "/allocation", allocationBody);
    backend.expect("DELETE", path + "/allocation", allocationBody);
    backend.expect("PUT", path + "/note", "{\"note\":\"Notiz\",\"version\":3}");
    backend.expect(
        "PATCH",
        path + "/delivered",
        "{\"delivered\":true,\"jobOrderId\":\"" + JOB_ORDER + "\",\"version\":2}");
  }

  @Test
  void orgUnitAndStolenMarkers() {
    for (int i = 0; i < 4; i++) {
      backend.answerJson("{}");
    }

    client.changeOrgUnit(ID, new InventoryItemOrgUnitChangeDto(1L, ORG_UNIT, true));
    client.bulkChangeOrgUnit(new BulkOrgUnitChangeRequest(List.of(ID), null, false));
    client.markStolen(ID, new InventoryItemStolenMarkDto(2L, true, null));
    client.bulkMarkStolen(new BulkStolenMarkRequest(List.of(ID), false));

    backend.expect(
        "POST",
        "/api/v1/inventory/" + ID + "/org-unit",
        "{\"version\":1,\"targetOwningOrgUnitId\":\"" + ORG_UNIT + "\",\"mergeStock\":true}");
    backend.expect(
        "POST",
        "/api/v1/inventory/bulk-org-unit",
        "{\"itemIds\":[\"" + ID + "\"],\"targetOwningOrgUnitId\":null,\"mergeStock\":false}");
    backend.expect(
        "POST",
        "/api/v1/inventory/" + ID + "/stolen",
        "{\"version\":2,\"stolen\":true,\"amount\":null}");
    backend.expect(
        "POST", "/api/v1/inventory/bulk-stolen", "{\"itemIds\":[\"" + ID + "\"],\"stolen\":false}");
  }

  @Test
  void deleteAll() {
    backend.answerEmpty();

    assertThat(client.deleteAll().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    backend.expect("DELETE", "/api/v1/inventory/all", null);
  }
}
