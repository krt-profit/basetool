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

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice;
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
    // The logistician caller is a non-admin, so the order-detail profit gate would otherwise
    // redirect to /orders/create. Stub the capability as a profit-eligible viewer so the detail
    // render path runs.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", CapabilityFlagsAdvice.CapabilitiesResponse.class))
        .thenReturn(new CapabilityFlagsAdvice.CapabilitiesResponse(true, true, true));
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
    // Given: an ITEM order with one fully-derived top-level item and one sub-assembly line whose
    // blueprint derived no material (empty materials list -> the no-materials banner must appear).
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

    // When (German render so the negative stock-column assertion below is locale-stable).
    MvcResult result =
        mockMvc
            .perform(
                get("/orders/" + orderId)
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn();

    String html = result.getResponse().getContentAsString();

    // Then: the ITEM-kind chip and ordered items render.
    assertThat(html).as("ITEM kind badge").contains("order-kind-item");
    assertThat(html)
        .as("does not show the MATERIAL chip on an item order")
        .doesNotContain("order-kind-material");
    assertThat(html).as("top-level ordered item name").contains("A03 Sniper Rifle");
    assertThat(html).as("sub-assembly ordered item name").contains("A03 Optic Scope");

    // Then: the sub-assembly provenance marker tags the adopted line.
    assertThat(html).as("sub-assembly provenance tag").contains("subassembly-tag");

    // Then: the aggregated-materials panel shows one Gut and one Keine row.
    assertThat(html).as("aggregated material name (GOOD)").contains("AcryliPlex Composite");
    assertThat(html).as("aggregated material name (NONE)").contains("Agricium");
    assertThat(html).as("GOOD quality badge").contains("quality-good");
    assertThat(html).as("NONE quality badge").contains("quality-none");

    // Then: the aggregated table surfaces the "Vorhanden" (linked stock) column, rendering each
    // bucket's currentStock — acryliPlex's 3.0 SCU distinguishes it from its 7.5 SCU Gesamtmenge.
    // The German render (Accept-Language: de) formats the decimal with a comma separator.
    assertThat(html).as("Vorhanden column header (de)").contains("Vorhanden");
    assertThat(html)
        .as("linked-stock value rendered in the Vorhanden column")
        .contains("3,000 SCU");

    // Then: the no-materials banner appears and names the unresolved sub-assembly line.
    int bannerIndex = html.indexOf("alert-warning");
    assertThat(bannerIndex).as("no-materials warning banner").isGreaterThan(0);
    assertThat(html.indexOf("A03 Optic Scope", bannerIndex))
        .as("unresolved item is listed inside the banner")
        .isGreaterThan(bannerIndex);

    // Then: the aggregated-material rows are now clickable linked-inventory drill-downs (the same
    // toggleInventory handler the MATERIAL requirement rows use), carrying the material id the AJAX
    // endpoint needs; and the Materialsammelübersicht link renders in the handover toolbar.
    assertThat(html)
        .as("aggregated rows are clickable inventory drill-downs")
        .contains("aggregated-material-row");
    assertThat(html)
        .as("aggregated drill-down rows carry the material id")
        .contains("data-material-id=");
    assertThat(html)
        .as("item-collection link renders for the item order")
        .contains("/item-collection");

    // Then: the MATERIAL requirement table is still gated out for item orders — assert on its
    // unique
    // 'Im Lager' stock column header, which is absent from both the aggregated table and the
    // always-rendered edit modal. Relies on the German render selected via Accept-Language above.
    assertThat(html).as("material requirement table gated out").doesNotContain("Im Lager");
  }

  @Test
  void itemOrderDetail_AggregatedRows_AreDrilldownsAndGuardClaimControls() throws Exception {
    // Given: a public SK item order — the aggregated material carries openAmount + a claim, so the
    // claim columns render. The aggregated rows must become clickable inventory drill-downs while
    // the claim controls inside them carry data-claim-control, so a claim click does not also
    // trigger the row drill-down (the two delegated click listeners fire independently).
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

    // When
    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Then: the aggregated row is a clickable drill-down carrying exactly the attributes the shared
    // toggleInventory handler reads (order id, material id, amount type, and the toggle trigger).
    assertThat(html).as("clickable drill-down class").contains("aggregated-material-row");
    assertThat(html)
        .as("drill-down trigger on the row")
        .contains("data-trigger=\"od-toggle-inventory\"");
    assertThat(html)
        .as("drill-down material id")
        .contains("data-material-id=\"" + agricium.id() + "\"");
    assertThat(html).as("drill-down order id").contains("data-order-id=\"" + orderId + "\"");
    assertThat(html).as("drill-down amount type").contains("data-amount-type=\"SCU\"");

    // Then: the claim controls inside the row carry data-claim-control so the row drill-down is
    // suppressed when a claim button (rather than a plain cell) is clicked.
    assertThat(html).as("claim controls guard the drill-down").contains("data-claim-control");

    // Then: the Itemsammelübersicht link targets the per-order item-collection page.
    assertThat(html).as("item-collection link").contains("/orders/" + orderId + "/item-collection");
  }

  @Test
  void itemOrderDetail_AllDelivered_StillShowsItemCollectionButton() throws Exception {
    // Given: a fully-delivered item order (3 ordered, 3 delivered -> 0 outstanding). The handover
    // button is gated out, but the Materialsammelübersicht button must stay reachable in the
    // handover toolbar — mirroring the status-independent MATERIAL handover toolbar.
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

    // When
    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Then: the handover button is gated out (no outstanding lines)...
    assertThat(html)
        .as("handover button hidden once fully delivered")
        .doesNotContain("data-testid=\"item-handover-open\"");
    // ...but the Itemsammelübersicht button is still rendered in the toolbar.
    assertThat(html)
        .as("item-collection button stays reachable after delivery")
        .contains("/orders/" + orderId + "/item-collection");
    // Delivery-gating message split (REQ-ORDERS-025): once every ordered unit is delivered
    // (isFullyDelivered), the "all items delivered" note shows and the produce-first hint does not.
    assertThat(html)
        .as("all-delivered note shows once fully delivered")
        .contains("data-testid=\"item-handover-all-delivered\"");
    assertThat(html)
        .as("produce-first hint hidden once fully delivered")
        .doesNotContain("data-testid=\"item-handover-none-manufactured\"");
    // The Herstellung surface folded into the items tab (#1317) — there is no separate tab/pane.
    assertThat(html)
        .as("no separate Herstellung tab")
        .doesNotContain("id=\"tab-production\"")
        .doesNotContain("id=\"pane-production\"");
  }

  @Test
  void itemOrderDetail_NotFullyDelivered_FoldsHerstellungIntoItemsTabWithProduceFirstHint()
      throws Exception {
    // Given: a not-yet-delivered item order (3 ordered, 0 manufactured, 0 delivered) with a
    // material,
    // viewed by a logistician. Nothing is manufactured-but-undelivered (no handover button) and the
    // order is not fully delivered -> the produce-first hint shows, not the "all delivered" note;
    // and
    // the production surface folds into the items tab rather than a separate Herstellung tab.
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

    // When
    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Then: the Herstellung surface is in the items tab — the record button and the chevron that
    // reveals the per-unit demand render there, with no separate Herstellung tab/pane.
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
    // And the message split: while not fully delivered, the produce-first hint shows and the
    // all-delivered note does not.
    assertThat(html)
        .as("produce-first hint shows while not fully delivered")
        .contains("data-testid=\"item-handover-none-manufactured\"");
    assertThat(html)
        .as("all-delivered note hidden while not fully delivered")
        .doesNotContain("data-testid=\"item-handover-all-delivered\"");
  }

  @Test
  void itemOrderDetail_RendersHandoverModalAndHistory() throws Exception {
    // Given: an item order with one outstanding line (3 ordered, 1 delivered -> 2 outstanding) and
    // one already-recorded item handover. The handover button + modal must render (outstanding > 0)
    // and the history row must offer a PDF delivery note.
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

    // When
    MvcResult result =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn();

    String html = result.getResponse().getContentAsString();

    // Then: the handover button and modal render (an outstanding line exists), targeting the
    // item-handover POST and exposing one bind-able line row.
    assertThat(html).as("item-handover open button").contains("data-testid=\"item-handover-open\"");
    assertThat(html).as("item-handover modal").contains("id=\"item-handover-modal\"");
    assertThat(html).as("modal posts to the item-handover endpoint").contains("/item-handovers");
    assertThat(html)
        .as("line amount input bound by request-param name")
        .contains("entries[0].amount");
    assertThat(html)
        .as("line id hidden input bound by request-param name")
        .contains("entries[0].jobOrderItemId");

    // Then: the history table shows the recorded handover with a PDF download trigger.
    assertThat(html).as("item-handover history row").contains("data-testid=\"item-handover-row\"");
    assertThat(html).as("PDF download trigger").contains("od-download-item-report");
    assertThat(html).as("recipient handle in history").contains("Recipient");
  }

  @Test
  void materialOrder_skResponsible_RendersClaimColumns() throws Exception {
    // Given: a public SK MATERIAL order (openAmount populated) with one squadron claim of 6 against
    // a required 10 → 4 open. The backend signals SK-ness by populating openAmount.
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
    // Given: a private squadron MATERIAL order — the backend leaves openAmount null and claims
    // empty, so the detail page renders no claim columns.
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
    // Given: a MATERIAL order mixing an SCU material (10 required, 2.5 in stock -> 7.5 SCU open)
    // and
    // a PIECE material (5 required, 1 in stock -> 4 Stück open). SCU and pieces are
    // incommensurable,
    // so the "Offene Menge" KPI tile must render them as two separate numbers, not 7.5 + 4 = 11.5.
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

    // Scope the assertions to the KPI band (between #order-kpi-results and the tab navigation) so
    // the
    // material-table amounts below cannot satisfy them by accident.
    int kpiStart = html.indexOf("order-kpi-results");
    int kpiEnd = html.indexOf("tab-nav", kpiStart);
    assertThat(kpiStart).as("KPI band present").isGreaterThan(0);
    assertThat(kpiEnd).as("tab navigation follows the KPI band").isGreaterThan(kpiStart);
    String kpiBand = html.substring(kpiStart, kpiEnd);

    // The SCU sum is 7,500 (German decimal comma) and stays SCU; the piece sum is 4 and renders
    // with
    // the Stück unit. Neither is the summed 11,500 that the old single-accumulator tile would show.
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
    // Given: a member of the responsible org unit — the members-only coverage endpoint returns
    // data.
    // Alice owns the Sniper Rifle blueprint; the Optic Scope blueprint is a coverage gap (count 0).
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

    // When (German render so the gap marker assertion is locale-stable).
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

    // Then: the coverage section, the owning member, the per-item coverage table and the gap marker
    // for the unowned item all render.
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
    // REQ-ORDERS-021 / #822: the variant-counting toggle is shown to an editor (logistician), wired
    // to the AJAX trigger, and reflects the order's countBlueprintsWithVariants flag
    // (oneLineItemOrder
    // defaults it on, so the checkbox is checked).
    assertThat(html)
        .as("variant-counting toggle rendered for the editor")
        .contains("data-trigger=\"od-toggle-bp-counting\"")
        .contains("bp-coverage__mode");
  }

  // REQ-ORDERS-021 / #822: the live toggle re-renders ONLY the coverage panel via a fragment swap
  // (GET /orders/{id}?fragment=blueprint-owners -> "orders-detail :: blueprintOwnersSection"). The
  // response must be the panel fragment alone (toggle + lists), never the full page chrome.
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

  // Log-noise / wasted round-trip guard: the members-only coverage endpoint must be hit ONLY for
  // the two renders that actually consume the attribute — the full page and its own
  // fragment=blueprint-owners swap. A swap of any other section (header/items/kpi/…) discards the
  // attribute, so re-fetching it there was pure waste and — since the endpoint 403s for a
  // non-member of the responsible org unit — spammed the backend log with a WARN on every unrelated
  // swap an open detail page issued (133 identical ACCESS_DENIED warnings from one viewer in a
  // single 30-min session). Pin that a non-blueprint section swap issues no coverage call.
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
    // Given: a non-member viewing a public SK item order — the members-only coverage endpoint is
    // forbidden. The page controller swallows the failure; the section must be absent, not fatal.
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    when(backendApiClient.get(
            eq("/api/v1/orders/" + orderId + "/item-blueprint-owners"),
            eq(JobOrderItemBlueprintOwnersDto.class)))
        .thenThrow(new RuntimeException("forbidden"));

    // When
    String html =
        mockMvc
            .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Then: the detail page still renders, but without the members-only coverage section.
    assertThat(html)
        .as("coverage section omitted when the members-only endpoint is forbidden")
        .doesNotContain("data-testid=\"blueprint-owners-section\"");
  }

  // covers REQ-INV-032 (the production modal renders the book-in section: server-side-search
  // location combobox (remote-locations, REQ-FE-016 — no preloaded catalog), remote-users owner
  // picker seeded + preselected with the acting user, the org-unit picker shell orders-detail.js
  // repopulates per owner, and the personal / default-on "dem Auftrag zuordnen" controls)
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

    // Then: the location picker is a server-side-search combobox (remote-locations) that renders
    // with no preloaded catalog options.
    int locationAt = html.indexOf("id=\"production-location\"");
    assertThat(locationAt).as("book-in location picker rendered").isGreaterThan(0);
    assertThat(html.substring(locationAt, html.indexOf('>', locationAt)))
        .as("location picker carries the remote-locations combobox marker")
        .contains("data-krt-combobox=\"remote-locations\"");
    // The owner picker is a remote-users combobox seeded + preselected with the acting user.
    assertThat(html)
        .as("book-in owner picker carries the remote-users marker")
        .contains("id=\"production-owner\"")
        .contains("data-krt-combobox=\"remote-users\"");
    assertThat(html).as("acting-user seed option").contains("Logi Stician");
    assertThat(html)
        .as("acting-user id stamped for the JS owner fallback")
        .contains("data-acting-user-id=\"" + userId + "\"");
    // The acting-user display name is stamped too, so a book-in reset can re-seed the owner
    // combobox's visible label even after a remote search evicted the server-seeded option
    // (remote mode: a bare setValue(id) with no label would blank the field, REQ-FE-016).
    assertThat(html)
        .as("acting-user name stamped for the owner-picker label re-seed")
        .contains("data-acting-user-name=\"Logi Stician\"");
    // The org-unit picker shell and the two checkboxes render; "dem Auftrag zuordnen" defaults on.
    assertThat(html).as("org-unit picker shell").contains("id=\"production-orgunit\"");
    assertThat(html).as("personal checkbox").contains("id=\"production-personal\"");
    int allocateAt = html.indexOf("id=\"production-allocate\"");
    assertThat(allocateAt).as("allocate checkbox rendered").isGreaterThan(0);
    String allocateTag = html.substring(allocateAt, html.indexOf('>', allocateAt));
    assertThat(allocateTag).as("allocate checkbox defaults on").contains("checked");
  }

  // No-double-fetch guard for the parallelized logistician fan-out (#768). The order-detail render
  // splits addOwnerPickerOptions into a fetch step + an apply step so the requesting-org-unit list
  // can be loaded on a ParallelPageLoader worker thread alongside users/materials/squadrons. Pin
  // that each of the four independent lookups still fires exactly once: a regression that left the
  // old serial addOwnerPickerOptions(model) in place on top of the parallel fetch would double the
  // owner-picker round-trip and trip times(1) here.
  @Test
  void detailRender_logistician_fetchesEachFanOutLookupExactlyOnce() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/orders/" + orderId), eq(JobOrderDto.class)))
        .thenReturn(oneLineItemOrder(orderId));
    // The authenticated requesting picker sources the all-kinds catalog; return one option so the
    // fetch path runs end to end (the apply step derives the responsible subset from it) and the
    // picker never falls back to the /active catalog.
    when(backendApiClient.getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef()))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    UUID.randomUUID(), "Profit Spezialkommando", "PSK", "SPECIAL_COMMAND", true)));

    mockMvc
        .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
        .andExpect(status().isOk());

    // Each of the independent logistician lookups fires exactly once — the parallel fan-out does
    // not
    // duplicate any round-trip. The assignee-add picker no longer preloads the roster (#1193: it
    // searches /users/search on demand), so the page issues no /api/v1/users?size=1000 fetch.
    verify(backendApiClient, times(1))
        .getCached(eq(CachedCatalog.ORG_UNITS_ACTIVE_ALL_KINDS), anyTypeRef());
    verify(backendApiClient, never()).get(eq("/api/v1/users?size=1000"), anyTypeRef());
    verify(backendApiClient, times(1))
        .getCached(eq(CachedCatalog.MATERIALS_JOB_ORDER), anyTypeRef(), eq(true));
    verify(backendApiClient, times(1))
        .getCached(eq(CachedCatalog.SQUADRONS), anyTypeRef(), eq(true));
  }

  // ---- Earmarked item stock, rendered inline in the item expand row (REQ-ORDERS-028) ----

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

  // covers REQ-ORDERS-028 (the earmarked item stock is rendered inline in the ordered item's expand
  // row — who holds each unit and where — read-only, with NO delivered toggle: collecting moves to
  // the Itemsammelübersicht page)
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

    // The ordered line is expandable, and its expand row carries the earmarked stock heading, the
    // owner, the location and the this-order slice with the total-stock context (whole units).
    assertThat(html)
        .as("expand chevron on the item row")
        .contains("data-trigger=\"od-toggle-demand\"");
    assertThat(html).as("inline earmarked-stock block").contains("class=\"od-item-stock-inline\"");
    assertThat(html).as("stock owner rendered inline").contains("Alice");
    assertThat(html).as("stock location rendered inline").contains("Lorville");
    assertThat(html).as("total-stock context on a partial earmark").contains("von 4 im Bestand");
    // The inline view is read-only: the delivered toggle (and the removed standalone panel) are
    // gone.
    assertThat(html)
        .as("no delivered toggle in the read-only inline stock")
        .doesNotContain("data-trigger=\"od-item-stock-delivered\"");
    assertThat(html)
        .as("the standalone Item-Bestand panel is removed")
        .doesNotContain("data-testid=\"order-item-stock-panel\"");
  }

  // covers REQ-ORDERS-029 (the backend blanks owner/location for a requesting-side viewer; the
  // inline stock must render a dash rather than an empty cell, while keeping the amount/progress)
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

  // covers REQ-ORDERS-028 (no earmarked stock -> no inline stock block; a line with material demand
  // is still expandable for the demand)
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
    // The line still carries material demand, so its expand chevron is present for the demand
    // block.
    assertThat(html).as("demand still expandable").contains("data-trigger=\"od-toggle-demand\"");
  }

  // covers REQ-ORDERS-028 (the `items` fragment swap carries the inline earmarked stock)
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

  // covers REQ-ORDERS-028 (a MATERIAL order renders no inline item stock and never fetches it)
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
    // Given: a public SK item order whose single line is half manufactured (2 of 4 made). The
    // aggregated row therefore carries the OUTSTANDING demand as totalQuantity (6 SCU) while the
    // claim base stays the FULL requirement (openAmount 12 SCU, nothing claimed yet). Rendered as a
    // column that put an "Offen" of 12 next to a "Gesamtmenge" of 6 in the same row, which reads as
    // a contradiction - so the column is gone. The claim action and the data-open max it feeds into
    // the modal must survive the removal, otherwise SK squadrons can no longer sign up at all.
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

    // When
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

    // Then: no open-amount cell anywhere on the page. An ITEM order never renders the MATERIAL
    // requirement table, so this marker class can only originate from the aggregated table -
    // asserting on the word "Offen" would false-positive on the OPEN status label and the
    // "Offene Materialmenge" KPI tile, which both legitimately stay.
    assertThat(html).as("no claim open-amount cell").doesNotContain("claim-open-amount");

    // Then: the claim column itself and its action survive, with the full-requirement remainder
    // still handed to the modal as the max.
    assertThat(html).as("claims column header (de)").contains("Eingetragen");
    assertThat(html).as("claim chips container").contains("claim-chips");
    assertThat(html).as("claim add action").contains("btn-claim-add");
    assertThat(html)
        .as("claimable remainder still fed to the modal")
        .contains("data-open=\"12.0\"");
  }
}
