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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderHandoverItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the order detail page through the real Thymeleaf template and pins the stolen danger chip
 * on the linked-but-unneeded inventory rows and on the material handover lines (REQ-INV-053).
 */
@SpringBootTest
class JobOrderStolenChipRenderTest {

  private static final String STOLEN_CHIP = "data-testid=\"stolen-chip\"";

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

  @Test
  void orderDetail_orphanedInventory_marksOnlyTheStolenRow() throws Exception {
    UUID orderId = UUID.randomUUID();
    stubOrder(materialOrder(orderId, List.of()));
    when(backendApiClient.get(
            eq("/api/v1/orders/{id}/inventory/orphaned"), anyTypeRef(), eq(orderId)))
        .thenReturn(List.of(inventory("Titanium", false), inventory("Quantanium", true)));

    String html = render(orderId);

    assertThat(countChips(html)).as("exactly one stolen chip on the page").isEqualTo(1);
    assertThat(enclosingElement(html, "Quantanium", "tr"))
        .as("the stolen row carries the chip")
        .contains(STOLEN_CHIP)
        .contains(">Gestohlen<");
    assertThat(enclosingElement(html, "Titanium", "tr"))
        .as("the legitimate row carries no chip")
        .doesNotContain(STOLEN_CHIP);
  }

  @Test
  void orderDetail_handoverLines_marksOnlyTheStolenLine() throws Exception {
    UUID orderId = UUID.randomUUID();
    stubOrder(
        materialOrder(
            orderId,
            List.of(
                handover(
                    orderId,
                    List.of(
                        handoverItem(material("Titanium"), false),
                        handoverItem(material("Quantanium"), true))))));

    String html = render(orderId);

    assertThat(countChips(html)).as("exactly one stolen chip on the page").isEqualTo(1);
    assertThat(enclosingElement(html, "Quantanium", "li"))
        .as("the stolen handover line carries the chip")
        .contains(STOLEN_CHIP)
        .contains(">Gestohlen<");
    assertThat(enclosingElement(html, "Titanium", "li"))
        .as("the legitimate handover line carries no chip")
        .doesNotContain(STOLEN_CHIP);
  }

  @Test
  void orderDetail_noStolenStock_rendersNoChip() throws Exception {
    UUID orderId = UUID.randomUUID();
    stubOrder(
        materialOrder(
            orderId,
            List.of(handover(orderId, List.of(handoverItem(material("Titanium"), false))))));
    when(backendApiClient.get(
            eq("/api/v1/orders/{id}/inventory/orphaned"), anyTypeRef(), eq(orderId)))
        .thenReturn(List.of(inventory("Laranite", false)));

    String html = render(orderId);

    assertThat(html).as("the orphaned inventory row is rendered").contains("Laranite");
    assertThat(html).as("the handover line is rendered").contains("Titanium");
    assertThat(html).as("no stolen chip anywhere").doesNotContain(STOLEN_CHIP);
  }

  /**
   * Stubs the order read the detail page issues first.
   *
   * @param order the order the backend returns.
   */
  private void stubOrder(@NotNull JobOrderDto order) {
    when(backendApiClient.get(eq("/api/v1/orders/{id}"), eq(JobOrderDto.class), eq(order.id())))
        .thenReturn(order);
  }

  /**
   * Renders the full order detail page in German as a LOGISTICIAN.
   *
   * @param orderId the order to open.
   * @return the rendered HTML.
   * @throws Exception when the request fails.
   */
  private String render(UUID orderId) throws Exception {
    return mockMvc
        .perform(
            get("/orders/" + orderId)
                .header("Accept-Language", "de")
                .with(authentication(logisticianToken(UUID.randomUUID()))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Builds an authenticated LOGISTICIAN principal, which sees the handover section.
   *
   * @param userId the principal's subject id.
   * @return the authentication token.
   */
  private static OAuth2AuthenticationToken logisticianToken(UUID userId) {
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

  /**
   * Builds an open, unredacted MATERIAL order with no material lines and the given handovers.
   *
   * @param orderId the order id.
   * @param handovers the recorded material handovers.
   * @return the order DTO.
   */
  private static JobOrderDto materialOrder(UUID orderId, List<JobOrderHandoverDto> handovers) {
    return new JobOrderDto(
        orderId,
        1,
        null,
        null,
        "Handle",
        null,
        1,
        "OPEN",
        "MATERIAL",
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        handovers,
        List.of(),
        Instant.now(),
        1L,
        null,
        false);
  }

  /**
   * Builds one SCU inventory entry linked to the order, with owner and location set.
   *
   * @param materialName the material name, unique within the rendered page.
   * @param stolen whether the entry is marked stolen.
   * @return the inventory DTO.
   */
  private static InventoryItemDto inventory(String materialName, boolean stolen) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        new UserReferenceDto(UUID.randomUUID(), "owner", null, "Owner", null),
        new MaterialReferenceDto(UUID.randomUUID(), materialName, "SCU"),
        null,
        new LocationReferenceDto(UUID.randomUUID(), "Everus Harbor"),
        500,
        12.5,
        false,
        stolen,
        List.of(),
        null,
        List.of(),
        null,
        null,
        null,
        1L,
        true,
        Instant.now());
  }

  /**
   * Builds a material handover to a fixed recipient carrying the given lines.
   *
   * @param orderId the order the handover belongs to.
   * @param items the handed-over lines.
   * @return the handover DTO.
   */
  private static JobOrderHandoverDto handover(UUID orderId, List<JobOrderHandoverItemDto> items) {
    return new JobOrderHandoverDto(
        UUID.randomUUID(), orderId, Instant.now(), "Recipient", "IRI", null, null, items, 1L);
  }

  /**
   * Builds one handover line of 10 SCU at quality 500.
   *
   * @param material the handed-over material.
   * @param stolen whether the source stock was marked stolen.
   * @return the handover line DTO.
   */
  private static JobOrderHandoverItemDto handoverItem(MaterialDto material, boolean stolen) {
    return new JobOrderHandoverItemDto(
        UUID.randomUUID(), UUID.randomUUID(), material, 500, 10.0, "Everus Harbor", stolen, 1L);
  }

  /**
   * Builds an SCU material reference, enough for the handover line's unit-aware formatting.
   *
   * @param name the material name, unique within the rendered page.
   * @return the material DTO.
   */
  private static MaterialDto material(String name) {
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
   * Counts the stolen chips in the rendered page.
   *
   * @param html the rendered page.
   * @return the number of {@code data-testid="stolen-chip"} occurrences.
   */
  private static int countChips(String html) {
    return html.split(STOLEN_CHIP, -1).length - 1;
  }

  /**
   * Returns the innermost {@code <tag>} element of the page that encloses the first occurrence of
   * the marker.
   *
   * @param html the rendered page.
   * @param marker text that occurs first inside the wanted element.
   * @param tag the element name, without angle brackets.
   * @return the element's markup from its opening tag up to its closing tag.
   */
  private static String enclosingElement(String html, String marker, String tag) {
    int at = html.indexOf(marker);
    assertThat(at).as("%s is rendered", marker).isNotNegative();
    int start = html.lastIndexOf("<" + tag, at);
    int end = html.indexOf("</" + tag + ">", at);
    assertThat(start).as("%s sits inside a <%s>", marker, tag).isNotNegative();
    assertThat(end).as("%s sits inside a closed <%s>", marker, tag).isGreaterThan(at);
    return html.substring(start, end);
  }
}
