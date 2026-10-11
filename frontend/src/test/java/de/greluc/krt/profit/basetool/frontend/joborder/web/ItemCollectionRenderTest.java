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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the Itemsammelübersicht page ({@code /orders/{id}/item-collection}) through the real
 * Thymeleaf template so a broken expression — in particular the per-group game-item name and the
 * per-entry owner/location/amount/delivered cells — fails the build rather than only surfacing at
 * runtime.
 */
@SpringBootTest
class ItemCollectionRenderTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private OAuth2AuthenticationToken logisticianToken(UUID userId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put(IdTokenClaimNames.SUB, userId.toString());
    claims.put("preferred_username", "logistician");
    OidcIdToken idToken =
        new OidcIdToken(
            "token-value",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(3600),
            claims);
    OidcUser oidcUser =
        new DefaultOidcUser(
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")), idToken);
    return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
  }

  @Test
  void itemCollection_rendersEarmarkedStockRows() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();

    JobOrderItemStockGroupDto group =
        new JobOrderItemStockGroupDto(
            new InventoryGameItemReferenceDto(
                UUID.randomUUID(), "Cirrus Optic Scope", "Behring", "WEAPON_ATTACHMENT"),
            3,
            1,
            3L,
            List.of(
                new JobOrderItemStockEntryDto(
                    entryId, 7L, "Alice", ownerId, "Lorville", locationId, 4L, 3L, false)));
    when(backendApiClient.get(contains("/item-stock"), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of(group));
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of(new LocationReferenceDto(locationId, "Lorville")));

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId + "/item-collection")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("collection table").contains("id=\"item-collection-table\"");
    assertThat(html)
        .as("list pattern: page head with the back link to the order, stacked table, no HUD box")
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*href=\"/orders/" + orderId + "\"")
        .containsPattern("<h1>Itemsammelübersicht</h1>")
        .contains("class=\"data-table data-table--stack collection-table\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("btn--cta");
    assertThat(html)
        .as("game-item name in the group row")
        .containsPattern("class=\"collection-group__title\">Cirrus Optic Scope<");
    assertThat(html)
        .as("collection progress over all groups and per group")
        .contains("data-collection-progress=\"total\"")
        .contains(">0 % geliefert<")
        .contains(">0 von 3 geliefert<")
        .contains("data-allocated=\"3\"");
    assertThat(html).as("owner seeded into the combobox").contains("Alice");
    assertThat(html).as("total-stock context").contains("von 4 im Bestand");
    assertThat(html)
        .as("delivered toggle bound to the order")
        .contains("data-job-order-id=\"" + orderId + "\"");
    assertThat(html)
        .as("row keyed by the entry id")
        .contains("data-inventory-id=\"" + entryId + "\"");
  }

  @Test
  void itemCollection_emptyStock_rendersEmptyState() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.get(contains("/item-stock"), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of());

    String html =
        mockMvc
            .perform(
                get("/orders/" + orderId + "/item-collection")
                    .header("Accept-Language", "de")
                    .with(authentication(logisticianToken(userId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html).as("empty state, no table").doesNotContain("id=\"item-collection-table\"");
    assertThat(html)
        .as("empty state with title and sentence, no progress bar")
        .contains("data-testid=\"empty-state\"")
        .contains("Kein Item-Bestand für diesen Auftrag vorhanden.")
        .doesNotContain("data-collection-progress");
  }
}
