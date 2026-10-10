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

package de.greluc.krt.profit.basetool.frontend.materialexchange.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.materialexchange.client.MaterialExchangeBackendClient.BoardFilter;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeItemReleaseRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeOfferUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialExchangeReleaseRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialItemRequestCreateRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestCreateRequest;
import de.greluc.krt.profit.basetool.frontend.materialexchange.model.MaterialRequestUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link MaterialExchangeBackendClient} sends (plan F3), each the exact request
 * the Materialbörse controller sent before the client existed, the typed bodies carrying the same
 * keys the browser posted.
 */
class MaterialExchangeBackendClientTest {

  private static final UUID ID = UUID.fromString("9c8b7a6f-5e4d-4c3b-8a2f-1e0d9c8b7a6f");
  private static final UUID ITEM = UUID.fromString("3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");
  private static final UUID MATERIAL = UUID.fromString("7b1e2c3d-4a5f-4e6d-8c7b-9a0f1e2d3c4b");
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":200,\"totalElements\":0,\"totalPages\":0}";
  private static final String COUNTS = "{\"all\":3,\"mine\":1}";

  private BackendClientHarness backend;
  private MaterialExchangeBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new MaterialExchangeBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void boardReadsCarryEveryPresentFilter() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);
    BoardFilter all = new BoardFilter("mein", 200, 500, 12.5, "qual", "Agricium Erz");
    BoardFilter none = new BoardFilter("alle", 200, null, null, " ", null);

    assertThat(client.offers(all, true)).isNotNull();
    assertThat(client.offers(none, false)).isNotNull();
    assertThat(client.requests(all)).isNotNull();
    assertThat(client.requests(none)).isNotNull();

    String filters = "?tab=mein&size=200&minQuality=500&minAmount=12.5&sort=qual";
    backend.expect(
        "GET",
        "/api/v1/material-exchange/offers" + filters + "&excludeStolen=true&q=Agricium%20Erz");
    backend.expect("GET", "/api/v1/material-exchange/offers?tab=alle&size=200");
    backend.expect("GET", "/api/v1/material-requests" + filters + "&q=Agricium%20Erz");
    backend.expect("GET", "/api/v1/material-requests?tab=alle&size=200");
  }

  @Test
  void detailsAndCounts() {
    backend.answerEmpty();
    backend.answerJson(COUNTS);
    backend.answerEmpty();
    backend.answerJson(COUNTS);

    client.offer(ID);
    assertThat(client.offerCounts().all()).isEqualTo(3L);
    client.request(ID);
    assertThat(client.requestCounts().mine()).isEqualTo(1L);

    backend.expect("GET", "/api/v1/material-exchange/offers/" + ID);
    backend.expect("GET", "/api/v1/material-exchange/counts");
    backend.expect("GET", "/api/v1/material-requests/" + ID);
    backend.expect("GET", "/api/v1/material-requests/counts");
  }

  @Test
  void pickers() {
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.offerableProducts("Gladius Wing", 25)).isEmpty();
    assertThat(client.offerableProducts(null, 25)).isEmpty();
    assertThat(client.releasableItems("widget", "ITEM")).isEmpty();
    assertThat(client.releasableItems(null, null)).isEmpty();
    assertThat(client.requestMaterials("agri", 25)).isNotNull();
    assertThat(client.requestMaterials(" ", 25)).isNotNull();

    backend.expect("GET", "/api/v1/blueprints/products/search?limit=25&q=Gladius%20Wing");
    backend.expect("GET", "/api/v1/blueprints/products/search?limit=25");
    backend.expect("GET", "/api/v1/material-exchange/releasable-items?kind=ITEM&q=widget");
    backend.expect("GET", "/api/v1/material-exchange/releasable-items");
    backend.expect("GET", "/api/v1/materials/search?size=25&search=agri");
    backend.expect("GET", "/api/v1/materials/search?size=25");
  }

  @Test
  void offerWrites() {
    for (int i = 0; i < 7; i++) {
      backend.answerEmpty();
    }

    client.release(new MaterialExchangeReleaseRequest(ITEM, 5.0, "Tausch"));
    client.releaseItem(new MaterialExchangeItemReleaseRequest("gladius", 2, null));
    client.updateOffer(ID, new MaterialExchangeOfferUpdateRequest(120.0, "neu", 0L));
    client.deactivateOffer(ID);
    client.deactivateOfferForItem(ITEM);
    client.registerInterest(ID);
    client.withdrawInterest(ID);

    backend.expect(
        "POST",
        "/api/v1/material-exchange/offers",
        "{\"inventoryItemId\":\"" + ITEM + "\",\"offeredAmount\":5.0,\"remark\":\"Tausch\"}");
    backend.expect(
        "POST",
        "/api/v1/material-exchange/item-offers",
        "{\"productKey\":\"gladius\",\"quantity\":2,\"remark\":null}");
    backend.expect(
        "PUT",
        "/api/v1/material-exchange/offers/" + ID + "/remark",
        "{\"offeredAmount\":120.0,\"remark\":\"neu\",\"version\":0}");
    backend.expect("POST", "/api/v1/material-exchange/offers/" + ID + "/deactivate", null);
    backend.expect("POST", "/api/v1/material-exchange/items/" + ITEM + "/deactivate", null);
    backend.expect("POST", "/api/v1/material-exchange/offers/" + ID + "/interest", null);
    backend.expect("DELETE", "/api/v1/material-exchange/offers/" + ID + "/interest", null);
  }

  @Test
  void requestWrites() {
    for (int i = 0; i < 6; i++) {
      backend.answerEmpty();
    }

    client.createMaterialRequest(new MaterialRequestCreateRequest(MATERIAL, 600, 12.5, "Dringend"));
    client.createItemRequest(new MaterialItemRequestCreateRequest("gladius", null, 1, null));
    client.updateRequest(ID, new MaterialRequestUpdateRequest(5.0, null, "", 2L));
    client.deactivateRequest(ID);
    client.signalFulfillment(ID);
    client.withdrawFulfillment(ID);

    backend.expect(
        "POST",
        "/api/v1/material-requests",
        "{\"materialId\":\""
            + MATERIAL
            + "\",\"minQuality\":600,\"requestedAmount\":12.5,\"remark\":\"Dringend\"}");
    backend.expect(
        "POST",
        "/api/v1/material-requests/item",
        "{\"productKey\":\"gladius\",\"minQuality\":null,\"quantity\":1,\"remark\":null}");
    backend.expect(
        "PUT",
        "/api/v1/material-requests/" + ID,
        "{\"desiredAmount\":5.0,\"minQuality\":null,\"remark\":\"\",\"version\":2}");
    backend.expect("POST", "/api/v1/material-requests/" + ID + "/deactivate", null);
    backend.expect("POST", "/api/v1/material-requests/" + ID + "/interest", null);
    backend.expect("DELETE", "/api/v1/material-requests/" + ID + "/interest", null);
  }
}
