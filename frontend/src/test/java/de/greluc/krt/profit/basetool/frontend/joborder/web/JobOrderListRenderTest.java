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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import de.greluc.krt.profit.basetool.frontend.support.QualityTierTestData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the order list renders an ITEM order's material column as its aggregated materials
 * with the ordered quantity and the collection progress {@code (currentStock +
 * manufacturedQuantity) / orderedQuantity}, and a MATERIAL order's lines with the requested amount
 * and the progress {@code (currentStock + handedOverAmount) / amount}.
 */
class JobOrderListRenderTest extends JobOrderPageMvcTestBase {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, true, true));
  }

  private MaterialDto material(String name, String quantityType) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        null,
        quantityType,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L);
  }

  @Test
  @WithMockUser
  void viewOrders_ItemOrder_RendersAggregatedMaterialsWithCollectionProgress() throws Exception {
    UUID orderId = UUID.randomUUID();
    MaterialDto quantanium = material("Quantanium", "SCU");

    JobOrderItemDto item =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "ZZZ Unrendered Item Name", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "ZZZ Unrendered Item Name", "wiki-zzz"),
            2,
            0,
            0,
            null,
            List.of(),
            false,
            1L);

    JobOrderDto order =
        new JobOrderDto(
            orderId,
            42,
            null,
            null,
            "Tester",
            null,
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(item),
            List.of(
                new AggregatedMaterialDto(
                    quantanium,
                    "NONE",
                    QualityTierTestData.forCode("NONE"),
                    10.0,
                    10.0,
                    0.0,
                    4.0,
                    List.of(),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            null,
            false,
            false,
            null,
            false,
            false,
            false,
            0L,
            0L,
            0L);

    when(reader.readOrderList(any()))
        .thenReturn(
            JobOrderPageStubs.list(new PageResponse<>(List.of(order), 0, 1000, 1L, 1, List.of())));

    MvcResult result = mockMvc.perform(get("/orders")).andExpect(status().isOk()).andReturn();

    String html = result.getResponse().getContentAsString();
    assertThat(html).contains("Quantanium");
    assertThat(html).contains("40 %");
    assertThat(html)
        .as("the overview no longer renders the ordered items in the Materialien column")
        .doesNotContain("ZZZ Unrendered Item Name");
  }

  @Test
  @WithMockUser
  void viewOrders_ItemOrder_KeepsTheOrderedAmountAndCountsBuiltMaterialAsProgress()
      throws Exception {
    AggregatedMaterialDto partlyBuilt =
        new AggregatedMaterialDto(
            material("Hadanite", "PIECE"),
            "NONE",
            QualityTierTestData.forCode("NONE"),
            10.0,
            40.0,
            30.0,
            6.0,
            List.of(),
            null);
    givenItemOrderList(partlyBuilt);

    String html =
        mockMvc
            .perform(get("/orders"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("Gefordert shows every ordered unit's demand, not the unmanufactured rest")
        .containsPattern("<span>40\\s")
        .doesNotContainPattern("<span>10\\s");
    assertThat(html)
        .as("progress counts linked stock plus the material built into manufactured units")
        .contains("90 %");
  }

  @Test
  @WithMockUser
  void viewOrders_FullyManufacturedItemOrder_ShowsCompleteProgress() throws Exception {
    AggregatedMaterialDto allBuilt =
        new AggregatedMaterialDto(
            material("Carinite", "PIECE"),
            "NONE",
            QualityTierTestData.forCode("NONE"),
            0.0,
            12.0,
            12.0,
            0.0,
            List.of(),
            null);
    givenItemOrderList(allBuilt);

    String html =
        mockMvc
            .perform(get("/orders"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).containsPattern("<span>12\\s").contains("100 %").doesNotContain(">0 %<");
  }

  /**
   * A material order's Gefordert stays the requested amount after handovers, and its progress
   * counts the handed-over amount beside the linked stock (REQ-ORDERS-017, REQ-ORDERS-077).
   */
  @Test
  @WithMockUser
  void viewOrders_MaterialOrder_KeepsTheRequestedAmountAndCountsHandedOverAsProgress()
      throws Exception {
    givenOrderList(
        "MATERIAL",
        List.of(
            materialLine(material("Hadanite", "PIECE"), 10.0, 4.0, 3.0),
            materialLine(material("Carinite", "PIECE"), 12.0, 12.0, 0.0)),
        List.of());

    String html =
        mockMvc
            .perform(get("/orders"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("Gefordert shows the requested amount, not what is left after the handovers")
        .containsPattern("<span>10\\s")
        .containsPattern("<span>12\\s")
        .doesNotContainPattern("<span>6\\s");
    assertThat(html)
        .as("progress counts linked stock plus the handed-over amount")
        .contains("70 %", "100 %")
        .doesNotContain(">0 %<");
  }

  private static JobOrderMaterialDto materialLine(
      MaterialDto material, double requested, double handedOver, double stock) {
    return new JobOrderMaterialDto(
        UUID.randomUUID(),
        material,
        null,
        QualityTierTestData.forCode("NONE"),
        requested,
        handedOver,
        requested - handedOver,
        stock,
        List.of(),
        null,
        1L);
  }

  private void givenItemOrderList(AggregatedMaterialDto aggregated) {
    givenOrderList("ITEM", List.of(), List.of(aggregated));
  }

  private void givenOrderList(
      String type, List<JobOrderMaterialDto> materials, List<AggregatedMaterialDto> aggregated) {
    JobOrderDto order =
        new JobOrderDto(
            UUID.randomUUID(),
            7,
            null,
            null,
            "Tester",
            null,
            null,
            "COMPLETED",
            type,
            true,
            materials,
            List.of(),
            aggregated,
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            null,
            false,
            false,
            null,
            false,
            false,
            false,
            0L,
            0L,
            0L);
    when(reader.readOrderList(any()))
        .thenReturn(
            JobOrderPageStubs.list(new PageResponse<>(List.of(order), 0, 1000, 1L, 1, List.of())));
  }

  @Test
  @WithMockUser
  void viewOrders_RendersResponsibleOrgUnitUnderIdAndType() throws Exception {
    JobOrderDto order =
        new JobOrderDto(
            UUID.randomUUID(),
            42,
            new SquadronReferenceDto(UUID.randomUUID(), "Alpha Squadron", "ALP"),
            null,
            "Tester",
            null,
            1,
            "OPEN",
            "MATERIAL",
            false,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            null,
            false,
            false,
            null,
            false,
            false,
            false,
            0L,
            0L,
            0L);

    when(reader.readOrderList(any()))
        .thenReturn(
            JobOrderPageStubs.list(new PageResponse<>(List.of(order), 0, 1000, 1L, 1, List.of())));

    MvcResult result = mockMvc.perform(get("/orders")).andExpect(status().isOk()).andReturn();

    String html = result.getResponse().getContentAsString();
    assertThat(html)
        .as("the responsible unit's shorthand renders as a squadron badge in the overview")
        .contains("ALP");
    assertThat(html)
        .as("the responsible unit's full name renders as the badge title")
        .contains("Alpha Squadron");
    assertThat(html)
        .as("the responsible-unit caption element renders under the id + type")
        .contains("order-responsible-label");
  }
}
