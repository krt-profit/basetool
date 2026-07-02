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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice;
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
            "/api/v1/me/capabilities", SquadronContextAdvice.CapabilitiesResponse.class))
        .thenReturn(new SquadronContextAdvice.CapabilitiesResponse(true, true));
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
            1,
            null,
            List.of(
                new JobOrderItemMaterialDto(UUID.randomUUID(), acryliPlex, 7.5, "GOOD", 1L),
                new JobOrderItemMaterialDto(UUID.randomUUID(), agricium, 12.0, "NONE", 1L)),
            1L);
    JobOrderItemDto subItem =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Optic Scope", "WEAPON_ATTACHMENT"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Optic Scope", "wiki-scope"),
            2,
            0,
            parentId,
            List.of(),
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
            1L);

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
        .as("material-collection link renders for the item order")
        .contains("/material-collection");

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
            null,
            List.of(new JobOrderItemMaterialDto(UUID.randomUUID(), agricium, 12.0, "NONE", 1L)),
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
            1L);
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

    // Then: the Materialsammelübersicht link targets the per-order material-collection page.
    assertThat(html)
        .as("material-collection link")
        .contains("/orders/" + orderId + "/material-collection");
  }

  @Test
  void itemOrderDetail_AllDelivered_StillShowsMaterialCollectionButton() throws Exception {
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
            null,
            List.of(
                new JobOrderItemMaterialDto(
                    UUID.randomUUID(), material("Agricium", "SCU"), 12.0, "NONE", 1L)),
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
            1L);
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
    // ...but the Materialsammelübersicht button is still rendered in the toolbar.
    assertThat(html)
        .as("material-collection button stays reachable after delivery")
        .contains("/orders/" + orderId + "/material-collection");
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
            1,
            null,
            List.of(
                new JobOrderItemMaterialDto(
                    UUID.randomUUID(), material("Agricium", "SCU"), 12.0, "NONE", 1L)),
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
            1L);

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
            1L);
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
            1L);
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

  private JobOrderDto oneLineItemOrder(UUID orderId) {
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            new BlueprintReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "wiki-a03"),
            3,
            0,
            null,
            List.of(
                new JobOrderItemMaterialDto(
                    UUID.randomUUID(), material("Agricium", "SCU"), 12.0, "NONE", 1L)),
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
        1L);
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
    when(backendApiClient.get(eq("/api/v1/org-units/active-all-kinds"), anyTypeRef()))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    UUID.randomUUID(), "Profit Spezialkommando", "PSK", "SPECIAL_COMMAND", true)));

    mockMvc
        .perform(get("/orders/" + orderId).with(authentication(logisticianToken(userId))))
        .andExpect(status().isOk());

    // Each of the four independent logistician lookups fires exactly once — the parallel fan-out
    // does not duplicate any round-trip.
    verify(backendApiClient, times(1)).get(eq("/api/v1/org-units/active-all-kinds"), anyTypeRef());
    verify(backendApiClient, times(1)).get(eq("/api/v1/users?size=1000"), anyTypeRef());
    verify(backendApiClient, times(1))
        .getCached(eq("/api/v1/materials/job-order"), anyTypeRef(), eq(true));
    verify(backendApiClient, times(1))
        .getCached(eq("/api/v1/squadrons?size=1000&sort=name,asc"), anyTypeRef(), eq(true));
  }
}
