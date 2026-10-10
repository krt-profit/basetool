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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderHandoverItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the order detail page on the detail pattern (REQ-UI-027, REQ-ORDERS-026): the back-link
 * page head with the translated status badge, the delivered-against-required progress head, the
 * tabs filtered per order kind with the empty ones hidden, one primary action per tab, and the
 * requester-limited view (REQ-ORDERS-023).
 */
@SpringBootTest
class OrdersDetailPatternRenderTest {

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  /** Builds the MockMvc and offers the job-order area through the layout capabilities. */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.get(LayoutResponses.PATH, LayoutContextLoader.MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, true, true));
  }

  /** A material order shows the head, the progress, its four tabs and one action per tab. */
  @Test
  void materialOrderRendersTheDetailPattern() throws Exception {
    UUID orderId = UUID.randomUUID();
    MaterialDto titanium = material("Titanium");
    JobOrderDto order =
        order(
            orderId,
            "MATERIAL",
            "IN_PROGRESS",
            List.of(materialLine(titanium, 300.0)),
            List.of(),
            List.of(handover(orderId, titanium, 100.0)),
            false);
    stubOrder(order);

    String html = render(orderId, "ROLE_LOGISTICIAN");

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\" href=\"/orders\"")
        .contains("<span>Aufträge</span>")
        .contains("<h1>Auftrag #7</h1>")
        .containsPattern("id=\"order-status-badge\" class=\"status-badge status-active\"")
        .contains(">In Bearbeitung<")
        .doesNotContain(">IN_PROGRESS<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("Zurück zur Übersicht");
    assertThat(pageActions(html)).doesNotContain("btn--cta");

    assertThat(html)
        .contains("data-testid=\"order-progress\"")
        .contains("class=\"meter od-progress__meter\"")
        .contains("data-krtm-width=\"25\"")
        .contains("100,000")
        .contains("400,000");

    assertThat(tabOrder(html))
        .containsExactly("tab-materials", "tab-handovers", "tab-assignees")
        .doesNotContain("tab-items", "tab-aggregated", "tab-item-handovers");
    assertThat(html)
        .contains("class=\"data-table data-table--stack od-table\"")
        .contains("class=\"cell-status\"")
        .contains(">Fehlend<");

    String handovers = section(html, "id=\"pane-handovers\"");
    assertThat(handovers.split("btn--cta", -1)).hasSize(2);
    assertThat(handovers)
        .contains("data-testid=\"order-handover-open\"")
        .contains("data-testid=\"order-handover-row\"");
    assertThat(section(html, "id=\"pane-assignees\"")).contains("data-testid=\"empty-state\"");
    assertThat(html)
        .containsPattern("<dialog[^>]*id=\"handover-modal\"")
        .contains("data-testid=\"order-handover-submit\"");

    try (InputStream template = getClass().getResourceAsStream("/templates/orders-detail.html")) {
      assertThat(template).isNotNull();
      assertThat(new String(template.readAllBytes(), StandardCharsets.UTF_8))
          .as("the template carries no migrated krtm class")
          .doesNotContainPattern("class=\"[^\"]*krtm-");
    }
  }

  /** An item order for a member shows its item tabs and hides the empty handover tab. */
  @Test
  void itemOrderFiltersTheTabsAndHidesEmptyOnes() throws Exception {
    UUID orderId = UUID.randomUUID();
    JobOrderItemDto line =
        new JobOrderItemDto(
            UUID.randomUUID(),
            new GameItemReferenceDto(UUID.randomUUID(), "A03 Sniper Rifle", "WEAPON"),
            null,
            4,
            2,
            1,
            null,
            List.of(),
            false,
            1L);
    stubOrder(order(orderId, "ITEM", "OPEN", List.of(), List.of(line), List.of(), false));

    String html = render(orderId, "ROLE_KRT_MEMBER");

    assertThat(html)
        .containsPattern("id=\"order-status-badge\" class=\"status-badge status-planned\"")
        .contains(">Offen<")
        .contains("data-krtm-width=\"25\"");
    assertThat(tabOrder(html))
        .containsExactly("tab-items", "tab-aggregated", "tab-item-handovers", "tab-assignees")
        .doesNotContain("tab-materials", "tab-handovers");
    assertThat(html)
        .containsPattern("id=\"tab-aggregated\"[^>]*hidden=\"hidden\"")
        .containsPattern("id=\"tab-item-handovers\"[^>]*hidden=\"hidden\"")
        .doesNotContain("data-testid=\"item-handover-open\"")
        .doesNotContain("data-trigger=\"od-open-production\"");
  }

  /** The requester sees the demand only, without progress, handovers or assignees. */
  @Test
  void requesterViewKeepsTheLimitedView() throws Exception {
    UUID orderId = UUID.randomUUID();
    stubOrder(
        order(
            orderId,
            "MATERIAL",
            "OPEN",
            List.of(materialLine(material("Titanium"), 300.0)),
            List.of(),
            List.of(),
            true));

    String html = render(orderId, "ROLE_KRT_MEMBER");

    assertThat(tabOrder(html)).containsExactly("tab-materials");
    assertThat(html)
        .doesNotContain("data-testid=\"order-progress\"")
        .doesNotContain("id=\"order-handovers-results\"")
        .doesNotContain("id=\"assignees-section\"")
        .doesNotContain("id=\"status-select\"")
        .doesNotContain("id=\"order-menu\"")
        .contains("data-modal-id=\"edit-modal\"");
  }

  /**
   * Stubs the order read the detail page issues first.
   *
   * @param order the order the backend returns
   */
  private void stubOrder(@NotNull JobOrderDto order) {
    when(backendApiClient.get(eq("/api/v1/orders/{id}"), eq(JobOrderDto.class), eq(order.id())))
        .thenReturn(order);
  }

  /**
   * Renders the order detail page in German for a principal with one role.
   *
   * @param orderId the order to open
   * @param role the granted authority
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String render(@NotNull UUID orderId, @NotNull String role) throws Exception {
    return mockMvc
        .perform(
            get("/orders/" + orderId)
                .header("Accept-Language", "de")
                .with(authentication(token(role))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Builds an OIDC principal carrying one role.
   *
   * @param role the granted authority
   * @return the authentication token
   */
  private static @NotNull OAuth2AuthenticationToken token(@NotNull String role) {
    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, UUID.randomUUID().toString());
    claims.put("preferred_username", "tester");
    OidcIdToken idToken =
        new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600), claims);
    OidcUser oidcUser =
        new DefaultOidcUser(Collections.singletonList(new SimpleGrantedAuthority(role)), idToken);
    return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
  }

  /**
   * Builds order number 7 with the given lines and handovers.
   *
   * @param orderId the order id
   * @param type {@code MATERIAL} or {@code ITEM}
   * @param status the order status
   * @param materials the material lines
   * @param items the item lines
   * @param handovers the material handovers
   * @param redacted whether the backend redacted the order for its requester
   * @return the order DTO
   */
  private static @NotNull JobOrderDto order(
      @NotNull UUID orderId,
      @NotNull String type,
      @NotNull String status,
      @NotNull List<JobOrderMaterialDto> materials,
      @NotNull List<JobOrderItemDto> items,
      @NotNull List<JobOrderHandoverDto> handovers,
      boolean redacted) {
    return new JobOrderDto(
        orderId,
        7,
        null,
        null,
        "Handle",
        null,
        1,
        status,
        type,
        true,
        materials,
        items,
        List.of(),
        List.of(),
        handovers,
        List.of(),
        Instant.now(),
        1L,
        null,
        redacted);
  }

  /**
   * Builds an open material line with no linked stock and no claims.
   *
   * @param material the material
   * @param amount the still-open amount
   * @return the line DTO
   */
  private static @NotNull JobOrderMaterialDto materialLine(
      @NotNull MaterialDto material, double amount) {
    return new JobOrderMaterialDto(
        UUID.randomUUID(), material, 0, null, amount, 0.0, List.of(), null, 1L);
  }

  /**
   * Builds a handover of one SCU line of the material.
   *
   * @param orderId the order the handover belongs to
   * @param material the handed-over material
   * @param amount the handed-over amount
   * @return the handover DTO
   */
  private static @NotNull JobOrderHandoverDto handover(
      @NotNull UUID orderId, @NotNull MaterialDto material, double amount) {
    JobOrderHandoverItemDto line =
        new JobOrderHandoverItemDto(
            UUID.randomUUID(), UUID.randomUUID(), material, 500, amount, "Everus", false, 1L);
    return new JobOrderHandoverDto(
        UUID.randomUUID(),
        orderId,
        Instant.now(),
        "Recipient",
        "IRI",
        null,
        null,
        List.of(line),
        1L);
  }

  /**
   * Builds an SCU material.
   *
   * @param name the material name
   * @return the material DTO
   */
  private static @NotNull MaterialDto material(@NotNull String name) {
    return new MaterialDto(
        UUID.randomUUID(),
        name,
        "MINERAL",
        "SCU",
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        true,
        false,
        true,
        1L);
  }

  /**
   * Cuts the page head's action area out of the page.
   *
   * @param html the rendered page
   * @return the markup from the page actions up to the order summary
   */
  private static @NotNull String pageActions(@NotNull String html) {
    int start = html.indexOf("class=\"page-actions\"");
    int end = html.indexOf("id=\"order-header-results\"");
    assertThat(start).as("page actions rendered").isPositive();
    assertThat(end).as("summary follows the head").isGreaterThan(start);
    return html.substring(start, end);
  }

  /**
   * Lists the tab ids of the tab navigation in document order.
   *
   * @param html the rendered page
   * @return the tab ids
   */
  private static @NotNull List<String> tabOrder(@NotNull String html) {
    int start = html.indexOf("role=\"tablist\"");
    int end = html.indexOf("class=\"tab-panes\"", start);
    String nav = html.substring(start, end);
    return Pattern.compile("id=\"(tab-[a-z-]+)\"")
        .matcher(nav)
        .results()
        .map(m -> m.group(1))
        .toList();
  }

  /**
   * Cuts one tab pane out of the page.
   *
   * @param html the rendered page
   * @param marker the pane's id attribute
   * @return the pane's markup up to its closing tag
   */
  private static @NotNull String section(@NotNull String html, @NotNull String marker) {
    int start = html.indexOf(marker);
    assertThat(start).as("%s rendered", marker).isPositive();
    int end = html.indexOf("</section>", start);
    return html.substring(start, end);
  }
}
