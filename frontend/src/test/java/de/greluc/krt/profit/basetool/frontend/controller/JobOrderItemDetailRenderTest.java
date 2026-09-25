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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.ClaimDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderBlueprintOwnerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemHandoverEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderRequiredBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the order-detail page renders the ITEM-order branch end to end: the ordered-items
 * table (with sub-assembly provenance and delivery progress), the internal aggregated-materials
 * panel (one row per material+quality with a Gut/Keine badge), and the warning banner for items
 * whose blueprint derived no procurable material. Renders through the real Thymeleaf template so a
 * broken expression in the new branch fails the build rather than only surfacing at runtime.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderItemDetailRenderTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, true, true));
  }

  private OAuth2AuthenticationToken logisticianToken(UUID userId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, userId.toString());
    claims.put("preferred_username", "logistician");
    OidcIdToken idToken =
        new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
    OidcUser oidcUser =
        new DefaultOidcUser(
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")), idToken);
    return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
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
  void itemOrderDetail_RendersItemTableAggregatedPanelAndUnresolvedBanner() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();

    MaterialDto acryliPlex = material("AcryliPlex Composite", "SCU");
    MaterialDto agricium = material("Agricium", "SCU");

    JobOrderItemDto topItem =
        new JobOrderItemDto(
            parentId,
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            3,
            2,
            1,
            null,
            List.of(
                new JobOrderItemMaterialDto(UUID.randomUUID(), acryliPlex, 7.5, "GOOD", 1L),
                new JobOrderItemMaterialDto(UUID.randomUUID(), agricium, 12.0, "NONE", 1L)),
            false,
            1L);
    JobOrderItemDto subItem =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Optic Scope", "WEAPON_ATTACHMENT"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Optic Scope", "wiki-scope"),
            2,
            0,
            0,
            parentId,
            List.of(),
            false,
            1L);

    JobOrderDto order =
        new JobOrderDto(
            orderId,
            7,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(topItem, subItem),
            List.of(
                new AggregatedMaterialDto(acryliPlex, "GOOD", 7.5, 3.0, List.of(), null),
                new AggregatedMaterialDto(agricium, "NONE", 12.0, 12.0, List.of(), null)),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);

    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    MvcResult result =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn();

    String html = result.getResponse().getContentAsString();

    assertThat(html).as("ITEM kind badge").contains("order-kind-item");
    assertThat(html)
        .as("does not show the MATERIAL chip on an item order")
        .doesNotContain("order-kind-material");
    assertThat(html).as("top-level ordered item name").contains("A03 Sniper Rifle");
    assertThat(html).as("sub-assembly ordered item name").contains("A03 Optic Scope");

    assertThat(html).as("sub-assembly provenance tag").contains("subassembly-tag");

    assertThat(html).as("aggregated material name (GOOD)").contains("AcryliPlex Composite");
    assertThat(html).as("aggregated material name (NONE)").contains("Agricium");
    assertThat(html).as("GOOD quality badge").contains("quality-good");
    assertThat(html).as("NONE quality badge").contains("quality-none");

    assertThat(html).as("Vorhanden column header (de)").contains("Vorhanden");
    assertThat(html)
        .as("linked-stock value rendered in the Vorhanden column")
        .contains("3,000 SCU");

    int bannerIndex = html.indexOf("alert-warning");
    assertThat(bannerIndex).as("no-materials warning banner").isGreaterThan(0);
    assertThat(html.indexOf("A03 Optic Scope", bannerIndex))
        .as("unresolved item is listed inside the banner")
        .isGreaterThan(bannerIndex);

    assertThat(html)
        .as("aggregated rows are clickable inventory drill-downs")
        .contains("aggregated-material-row");
    assertThat(html)
        .as("aggregated drill-down rows carry the material id")
        .contains("data-material-id=");
    assertThat(html)
        .as("item-collection link renders for the item order")
        .contains("/item-collection");

    assertThat(html).as("material requirement table gated out").doesNotContain("Im Lager");
  }

  @Test
  void itemOrderDetail_AggregatedRows_AreDrilldownsAndGuardClaimControls() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    MaterialDto agricium = material("Agricium", "SCU");

    ClaimDto claim =
        new ClaimDto(
            UUID.randomUUID(),
            new SquadronReferenceDto(UUID.randomUUID(), "Alpha Flight", "ALF"),
            6.0,
            null,
            Instant.now(),
            1L);
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            3,
            0,
            0,
            null,
            List.of(new JobOrderItemMaterialDto(UUID.randomUUID(), agricium, 12.0, "NONE", 1L)),
            false,
            1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            11,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(line),
            List.of(new AggregatedMaterialDto(agricium, "NONE", 12.0, 4.0, List.of(claim), 6.0)),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("clickable drill-down class").contains("aggregated-material-row");
    assertThat(html)
        .as("drill-down trigger on the row")
        .contains("data-trigger=\"od-toggle-inventory\"");
    assertThat(html)
        .as("drill-down material id")
        .contains("data-material-id=\"" + agricium.id() + "\"");
    assertThat(html).as("drill-down order id").contains("data-order-id=\"" + orderId + "\"");
    assertThat(html).as("drill-down amount type").contains("data-amount-type=\"SCU\"");

    assertThat(html).as("claim controls guard the drill-down").contains("data-claim-control");

    assertThat(html).as("item-collection link").contains("/orders/" + orderId + "/item-collection");
  }

  @Test
  void itemOrderDetail_AllDelivered_StillShowsItemCollectionButton() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            3,
            3,
            3,
            null,
            List.of(
                new JobOrderItemMaterialDto(
                    UUID.randomUUID(), material("Agricium", "SCU"), 12.0, "NONE", 1L)),
            false,
            1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            12,
            null,
            null,
            "Handle",
            null,
            1,
            "COMPLETED",
            "ITEM",
            true,
            List.of(),
            List.of(line),
            List.of(
                new AggregatedMaterialDto(
                    material("Agricium", "SCU"), "NONE", 12.0, 12.0, List.of(), null)),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("handover button hidden once fully delivered")
        .doesNotContain("data-testid=\"item-handover-open\"");
    assertThat(html)
        .as("item-collection button stays reachable after delivery")
        .contains("/orders/" + orderId + "/item-collection");
    assertThat(html)
        .as("all-delivered note shows once fully delivered")
        .contains("data-testid=\"item-handover-all-delivered\"");
    assertThat(html)
        .as("produce-first hint hidden once fully delivered")
        .doesNotContain("data-testid=\"item-handover-none-manufactured\"");
    assertThat(html)
        .as("no separate Herstellung tab")
        .doesNotContain("id=\"tab-production\"")
        .doesNotContain("id=\"pane-production\"");
  }

  @Test
  void itemOrderDetail_NotFullyDelivered_FoldsHerstellungIntoItemsTabWithProduceFirstHint()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            3,
            0,
            0,
            null,
            List.of(
                new JobOrderItemMaterialDto(
                    UUID.randomUUID(), material("Agricium", "SCU"), 12.0, "NONE", 1L)),
            false,
            1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            12,
            null,
            null,
            "Handle",
            null,
            1,
            "IN_PROGRESS",
            "ITEM",
            true,
            List.of(),
            List.of(line),
            List.of(
                new AggregatedMaterialDto(
                    material("Agricium", "SCU"), "NONE", 12.0, 12.0, List.of(), null)),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("Herstellung erfassen button folded into the items tab")
        .contains("data-trigger=\"od-open-production\"");
    assertThat(html)
        .as("per-unit demand chevron present for a line with materials")
        .contains("data-trigger=\"od-toggle-demand\"");
    assertThat(html)
        .as("no separate Herstellung tab/pane")
        .doesNotContain("id=\"tab-production\"")
        .doesNotContain("id=\"pane-production\"");
    assertThat(html)
        .as("produce-first hint shows while not fully delivered")
        .contains("data-testid=\"item-handover-none-manufactured\"");
    assertThat(html)
        .as("all-delivered note hidden while not fully delivered")
        .doesNotContain("data-testid=\"item-handover-all-delivered\"");
  }

  @Test
  void itemOrderDetail_RendersHandoverModalAndHistory() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();

    JobOrderItemDto line =
        new JobOrderItemDto(
            lineId,
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            3,
            3,
            1,
            null,
            List.of(
                new JobOrderItemMaterialDto(
                    UUID.randomUUID(), material("Agricium", "SCU"), 12.0, "NONE", 1L)),
            false,
            1L);
    JobOrderItemHandoverDto handover =
        new JobOrderItemHandoverDto(
            handoverId,
            orderId,
            Instant.now(),
            "Recipient",
            null,
            null,
            List.of(
                new JobOrderItemHandoverEntryDto(
                    UUID.randomUUID(),
                    lineId,
                    new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
                    1)),
            1L);

    JobOrderDto order =
        new JobOrderDto(
            orderId,
            8,
            null,
            null,
            "Handle",
            null,
            1,
            "IN_PROGRESS",
            "ITEM",
            true,
            List.of(),
            List.of(line),
            List.of(
                new AggregatedMaterialDto(
                    material("Agricium", "SCU"), "NONE", 12.0, 12.0, List.of(), null)),
            List.of(),
            List.of(),
            List.of(handover),
            Instant.now(),
            1L,
            null,
            false);

    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    MvcResult result =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn();

    String html = result.getResponse().getContentAsString();

    assertThat(html).as("item-handover open button").contains("data-testid=\"item-handover-open\"");
    assertThat(html).as("item-handover modal").contains("id=\"item-handover-modal\"");
    assertThat(html).as("modal posts to the item-handover endpoint").contains("/item-handovers");
    assertThat(html)
        .as("line amount input bound by request-param name")
        .contains("entries[0].amount");
    assertThat(html)
        .as("line id hidden input bound by request-param name")
        .contains("entries[0].jobOrderItemId");

    assertThat(html).as("item-handover history row").contains("data-testid=\"item-handover-row\"");
    assertThat(html).as("PDF download trigger").contains("od-download-item-report");
    assertThat(html).as("recipient handle in history").contains("Recipient");
  }

  @Test
  void materialOrder_skResponsible_RendersClaimColumns() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    ClaimDto claim =
        new ClaimDto(
            UUID.randomUUID(),
            new SquadronReferenceDto(UUID.randomUUID(), "Alpha Flight", "ALF"),
            6.0,
            null,
            Instant.now(),
            1L);
    JobOrderMaterialDto mat =
        new JobOrderMaterialDto(
            UUID.randomUUID(),
            material("Agricium", "SCU"),
            null,
            10.0,
            0.0,
            List.of(claim),
            4.0,
            1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            9,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "MATERIAL",
            true,
            List.of(mat),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("claims column header (de)").contains("Eingetragen");
    assertThat(html).as("open column header (de)").contains("Offen");
    assertThat(html).as("claim chip rendered").contains("claim-chip");
    assertThat(html).as("claiming squadron shorthand").contains("ALF");
  }

  @Test
  void materialOrder_privateSquadron_HidesClaimColumns() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    JobOrderMaterialDto mat =
        new JobOrderMaterialDto(
            UUID.randomUUID(), material("Agricium", "SCU"), null, 10.0, 0.0, List.of(), null, 1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            10,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "MATERIAL",
            true,
            List.of(mat),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("no claims column on a private order").doesNotContain("Eingetragen");
    assertThat(html).as("no claim chips on a private order").doesNotContain("claim-chip");
  }

  @Test
  void kpiOpenAmount_SplitsScuAndPieceIntoSeparateNumbers() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    JobOrderMaterialDto scuMat =
        new JobOrderMaterialDto(
            UUID.randomUUID(), material("Agricium", "SCU"), null, 10.0, 2.5, List.of(), null, 1L);
    JobOrderMaterialDto pieceMat =
        new JobOrderMaterialDto(
            UUID.randomUUID(),
            material("Power Plant", "PIECE"),
            null,
            5.0,
            1.0,
            List.of(),
            null,
            1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            11,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "MATERIAL",
            true,
            List.of(scuMat, pieceMat),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    int kpiStart = html.indexOf("order-kpi-results");
    int kpiEnd = html.indexOf("tab-nav", kpiStart);
    assertThat(kpiStart).as("KPI band present").isGreaterThan(0);
    assertThat(kpiEnd).as("tab navigation follows the KPI band").isGreaterThan(kpiStart);
    String kpiBand = html.substring(kpiStart, kpiEnd);

    assertThat(kpiBand).as("open SCU sum rendered as SCU").contains("7,500");
    assertThat(kpiBand)
        .as("open PIECE sum rendered with the Stück unit (split happened)")
        .contains("Stück");
    assertThat(kpiBand).as("open PIECE value is the piece sum (4)").contains(">4<");
    assertThat(kpiBand)
        .as("SCU and pieces are never summed into one figure")
        .doesNotContain("11,500");
  }

  private JobOrderDto oneLineItemOrder(UUID orderId) {
    return oneLineItemOrder(orderId, UUID.randomUUID());
  }

  private JobOrderDto oneLineItemOrder(UUID orderId, UUID gameItemId) {
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(gameItemId, "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            3,
            0,
            0,
            null,
            List.of(
                new JobOrderItemMaterialDto(
                    UUID.randomUUID(), material("Agricium", "SCU"), 12.0, "NONE", 1L)),
            false,
            1L);
    return new JobOrderDto(
        orderId,
        21,
        null,
        null,
        "Handle",
        null,
        1,
        "OPEN",
        "ITEM",
        true,
        List.of(),
        List.of(line),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.now(),
        1L,
        null,
        false);
  }

  @Test
  void itemOrder_memberSeesBlueprintCoverageSection() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    JobOrderItemBlueprintOwnersDto coverage =
        new JobOrderItemBlueprintOwnersDto(
            List.of(
                new JobOrderRequiredBlueprintDto("a03 sniper rifle", "A03 Sniper Rifle", 1, true),
                new JobOrderRequiredBlueprintDto("a03 optic scope", "A03 Optic Scope", 0, true)),
            List.of(
                new JobOrderBlueprintOwnerDto("Alice", List.of("A03 Sniper Rifle"), true),
                new JobOrderBlueprintOwnerDto("Carla", List.of("A03 Sniper Rifle"), false)));
    when(backendApiClient.get(
            eq("/api/v1/orders/" + orderId + "/item-blueprint-owners"),
            eq(JobOrderItemBlueprintOwnersDto.class)))
        .thenReturn(coverage);

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("coverage section rendered")
        .contains("data-testid=\"blueprint-owners-section\"");
    assertThat(html).as("owning member display name").contains("Alice");
    assertThat(html).as("owned-blueprint product badge").contains("A03 Sniper Rifle");
    assertThat(html)
        .as("per-item coverage row present")
        .contains("data-testid=\"blueprint-coverage-row\"");
    assertThat(html).as("coverage gap marker for the unowned item").contains("Keine Abdeckung");
    assertThat(html)
        .as("variant-inclusive hint shown for the weapon coverage row")
        .contains("inkl. Varianten");
    assertThat(html).as("global-sharer owner is also listed").contains("Carla");
    assertThat(html)
        .as("discreet not-a-member hint shown for the global-sharer owner (REQ-INV-018)")
        .contains("kein Einheitsmitglied");
    assertThat(html)
        .as("coverage panel is rendered inside a collapsible details, expanded by default")
        .contains("data-testid=\"blueprint-owners-details\"")
        .contains("bp-coverage__summary");
    assertThat(html)
        .as("variant-counting toggle rendered for the editor")
        .contains("data-trigger=\"od-toggle-bp-counting\"")
        .contains("bp-coverage__mode");
  }

  @Test
  void itemOrder_blueprintOwnersFragment_rendersOnlyThePanel() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    JobOrderItemBlueprintOwnersDto coverage =
        new JobOrderItemBlueprintOwnersDto(
            List.of(
                new JobOrderRequiredBlueprintDto("a03 sniper rifle", "A03 Sniper Rifle", 1, true)),
            List.of(new JobOrderBlueprintOwnerDto("Alice", List.of("A03 Sniper Rifle"), true)));
    when(backendApiClient.get(
            eq("/api/v1/orders/" + orderId + "/item-blueprint-owners"),
            eq(JobOrderItemBlueprintOwnersDto.class)))
        .thenReturn(coverage);

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .param("fragment", "blueprint-owners")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("the swapped fragment carries the panel + the variant-counting toggle")
        .contains("data-testid=\"blueprint-owners-details\"")
        .contains("data-trigger=\"od-toggle-bp-counting\"");
    assertThat(html)
        .as("a fragment swap returns the panel alone, not the whole page")
        .doesNotContain("<html");
  }

  @Test
  void itemOrder_nonBlueprintFragmentSwap_doesNotFetchBlueprintCoverage() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));

    mockMvc
        .perform(
            get("/orders/" + orderId)
                .param("fragment", "items")
                .with(authentication(logisticianToken(userId))))
        .andExpect(status().isOk());

    verify(backendApiClient, never())
        .get(
            eq("/api/v1/orders/" + orderId + "/item-blueprint-owners"),
            eq(JobOrderItemBlueprintOwnersDto.class));
  }

  @Test
  void itemOrder_nonMember_blueprintCoverageSectionOmitted() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    when(backendApiClient.get(
            eq("/api/v1/orders/" + orderId + "/item-blueprint-owners"),
            eq(JobOrderItemBlueprintOwnersDto.class)))
        .thenThrow(new RuntimeException("forbidden"));

    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("coverage section omitted when the members-only endpoint is forbidden")
        .doesNotContain("data-testid=\"blueprint-owners-section\"");
  }

  @Test
  void itemOrder_productionModal_rendersBookInSection() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    when(backendApiClient.get(
            eq("/api/v1/users/me"),
            eq(de.greluc.krt.profit.basetool.frontend.model.dto.UserDto.class)))
        .thenReturn(
            new de.greluc.krt.profit.basetool.frontend.model.dto.UserDto(
                userId,
                "logistician",
                "Logi Stician",
                "Logi Stician",
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                false,
                true,
                null,
                null,
                1L,
                null,
                null));

    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    int locationAt = html.indexOf("id=\"production-location\"");
    assertThat(locationAt).as("book-in location picker rendered").isGreaterThan(0);
    assertThat(html.substring(locationAt, html.indexOf('>', locationAt)))
        .as("location picker carries the remote-locations combobox marker")
        .contains("data-krt-combobox=\"remote-locations\"");
    assertThat(html)
        .as("book-in owner picker carries the remote-users marker")
        .contains("id=\"production-owner\"")
        .contains("data-krt-combobox=\"remote-users\"");
    assertThat(html).as("acting-user seed option").contains("Logi Stician");
    assertThat(html)
        .as("acting-user id stamped for the JS owner fallback")
        .contains("data-acting-user-id=\"" + userId + "\"");
    assertThat(html)
        .as("acting-user name stamped for the owner-picker label re-seed")
        .contains("data-acting-user-name=\"Logi Stician\"");
    assertThat(html).as("org-unit picker shell").contains("id=\"production-orgunit\"");
    assertThat(html).as("personal checkbox").contains("id=\"production-personal\"");
    int allocateAt = html.indexOf("id=\"production-allocate\"");
    assertThat(allocateAt).as("allocate checkbox rendered").isGreaterThan(0);
    String allocateTag = html.substring(allocateAt, html.indexOf('>', allocateAt));
    assertThat(allocateTag).as("allocate checkbox defaults on").contains("checked");
  }

  @Test
  void detailRender_logistician_fetchesEachFanOutLookupExactlyOnce() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    when(backendApiClient.getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef()))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    UUID.randomUUID(), "Profit Spezialkommando", "PSK", "SPECIAL_COMMAND", true)));

    mockMvc
        .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
        .andExpect(status().isOk());

    verify(backendApiClient, times(1))
        .getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef());
    verify(backendApiClient, never()).get(eq("/api/v1/users?size=1000"), anyTypeRef());
    verify(backendApiClient, times(1))
        .getCached(eq(CachedCatalog.MATERIALS_JOB_ORDER), anyTypeRef());
    verify(backendApiClient, times(2)).getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef());
  }

  /**
   * One earmarked-stock group for the inline-expand render tests. {@code gameItemId} must match the
   * ordered line's game item so the template's per-item lookup finds the stock.
   */
  private static de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockGroupDto
      itemStockGroup(UUID entryId, UUID gameItemId) {
    return new de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockGroupDto(
        new de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto(
            gameItemId, "Cirrus Optic Scope", "Behring", "WEAPON_ATTACHMENT"),
        3,
        1,
        3L,
        List.of(
            new de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockEntryDto(
                entryId,
                7L,
                "Alice",
                UUID.randomUUID(),
                "Lorville",
                UUID.randomUUID(),
                4L,
                3L,
                false)));
  }

  @Test
  void itemOrder_rendersEarmarkedStockInlineInItemExpand() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId, gameItemId));
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId + "/item-stock"), anyTypeRef()))
        .thenReturn(List.of(itemStockGroup(entryId, gameItemId)));

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("expand chevron on the item row")
        .contains("data-trigger=\"od-toggle-demand\"");
    assertThat(html).as("inline earmarked-stock block").contains("class=\"od-item-stock-inline\"");
    assertThat(html).as("stock owner rendered inline").contains("Alice");
    assertThat(html).as("stock location rendered inline").contains("Lorville");
    assertThat(html).as("total-stock context on a partial earmark").contains("von 4 im Bestand");
    assertThat(html)
        .as("no delivered toggle in the read-only inline stock")
        .doesNotContain("data-trigger=\"od-item-stock-delivered\"");
    assertThat(html)
        .as("the standalone Item-Bestand panel is removed")
        .doesNotContain("data-testid=\"order-item-stock-panel\"");
  }

  @Test
  void itemOrder_redactedOwnerLocation_rendersDash() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockGroupDto redacted =
        new de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockGroupDto(
            new de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto(
                gameItemId, "Cirrus Optic Scope", "Behring", "WEAPON_ATTACHMENT"),
            3,
            1,
            3L,
            List.of(
                new de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockEntryDto(
                    entryId, 7L, null, null, null, null, 4L, 3L, false)));
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId, gameItemId));
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId + "/item-stock"), anyTypeRef()))
        .thenReturn(List.of(redacted));

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("inline earmarked stock still renders")
        .contains("class=\"od-item-stock-inline\"");
    assertThat(html).as("blanked owner/location render as a dash").contains("<td>—</td>");
    assertThat(html).as("progress is kept on a redacted row").contains("von 4 im Bestand");
  }

  @Test
  void itemOrder_noEarmarkedStock_rendersNoInlineStock() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId + "/item-stock"), anyTypeRef()))
        .thenReturn(List.of());

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("no inline earmarked-stock block without stock")
        .doesNotContain("class=\"od-item-stock-inline\"");
    assertThat(html).as("demand still expandable").contains("data-trigger=\"od-toggle-demand\"");
  }

  @Test
  void itemOrder_itemsFragment_includesInlineStock() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId, gameItemId));
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId + "/item-stock"), anyTypeRef()))
        .thenReturn(List.of(itemStockGroup(entryId, gameItemId)));

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .param("fragment", "items")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("the items fragment carries the inline earmarked stock")
        .contains("Alice")
        .contains("Lorville");
    assertThat(html)
        .as("a fragment swap returns the section alone, not the whole page")
        .doesNotContain("<html");
  }

  @Test
  void materialOrder_hasNoItemStock() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    JobOrderMaterialDto mat =
        new JobOrderMaterialDto(
            UUID.randomUUID(), material("Agricium", "SCU"), null, 10.0, 0.0, List.of(), null, 1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            22,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "MATERIAL",
            true,
            List.of(mat),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .as("no inline item stock on a material order")
        .doesNotContain("class=\"od-item-stock-inline\"");
    verify(backendApiClient, never())
        .get(eq("/api/v1/orders/" + orderId + "/item-stock"), anyTypeRef());
  }

  @Test
  void itemOrderDetail_AggregatedTable_DropsOpenColumnButKeepsTheClaimAction() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    MaterialDto agricium = material("Agricium", "SCU");
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            4,
            2,
            0,
            null,
            List.of(new JobOrderItemMaterialDto(UUID.randomUUID(), agricium, 12.0, "NONE", 1L)),
            false,
            1L);
    JobOrderDto order =
        new JobOrderDto(
            orderId,
            23,
            null,
            null,
            "Handle",
            null,
            1,
            "OPEN",
            "ITEM",
            true,
            List.of(),
            List.of(line),
            List.of(new AggregatedMaterialDto(agricium, "NONE", 6.0, 0.0, List.of(), 12.0)),
            List.of(),
            List.of(),
            List.of(),
            Instant.now(),
            1L,
            null,
            false);
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(order);

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("no claim open-amount cell").doesNotContain("claim-open-amount");

    assertThat(html).as("claims column header (de)").contains("Eingetragen");
    assertThat(html).as("claim chips container").contains("claim-chips");
    assertThat(html).as("claim add action").contains("btn-claim-add");
    assertThat(html)
        .as("claimable remainder still fed to the modal")
        .contains("data-open=\"12.0\"");
  }
}
