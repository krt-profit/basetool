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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryStackDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.support.CapabilityStubs;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Render coverage of the „Umzug ausstehend" marker in the global Lager (REQ-PATCH-023): the chip
 * beside a pending holder's stack and rows, the row actions disabled with the explanation for a
 * caller the lock refuses, and the actions left enabled for one it does not.
 */
@SpringBootTest
class InventoryRelocationMarkerRenderTest {

  private static final String CHIP = "data-testid=\"relocation-pending-chip\"";

  private static final String LOCKED_TITLE =
      "title=\"Gesperrt, bis das Mitglied für den Patch seine Home-Location gewählt hat.\"";

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private final UUID materialId = UUID.randomUUID();
  private final UUID locationId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void aPendingHoldersStackCarriesTheChipAndAnotherDoesNot() throws Exception {
    stub("/inventory/all/grouped", List.of(group(stack(true))));

    assertThat(render(get("/inventory?storage=all"))).containsOnlyOnce(CHIP);

    stub("/inventory/all/grouped", List.of(group(stack(false))));

    assertThat(render(get("/inventory?storage=all"))).doesNotContain(CHIP);
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void aLockedRowShowsTheChipAndDisablesItsActionsWithTheExplanation() throws Exception {
    CapabilityStubs.report(backendApiClient, LayoutResponses.logistician(true));
    stub("/inventory/all/stack/entries", page(entry(true, true)));

    String html = render(entriesRequest());

    assertThat(html)
        .containsOnlyOnce(CHIP)
        .containsPattern("data-trigger=\"inv-admin-bookout\"[^>]*disabled=\"disabled\"")
        .containsPattern("data-trigger=\"inv-admin-umbuchen\"[^>]*disabled=\"disabled\"")
        .containsPattern("data-trigger=\"inv-admin-open-note\"[^>]*disabled=\"disabled\"")
        .contains(LOCKED_TITLE)
        .doesNotContain("data-trigger=\"inv-admin-assoc-add-open\"")
        .doesNotContain("data-trigger=\"inv-admin-assoc-edit\"");
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void aPendingRowTheLockDoesNotRefuseKeepsItsActions() throws Exception {
    CapabilityStubs.report(backendApiClient, LayoutResponses.logistician(true));
    stub("/inventory/all/stack/entries", page(entry(true, false)));

    String html = render(entriesRequest());

    assertThat(html)
        .containsOnlyOnce(CHIP)
        .doesNotContainPattern("data-trigger=\"inv-admin-bookout\"[^>]*disabled")
        .doesNotContainPattern("data-trigger=\"inv-admin-open-note\"[^>]*disabled")
        .doesNotContain(LOCKED_TITLE)
        .contains("data-trigger=\"inv-admin-assoc-add-open\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void theMaterialDrillDownMarksAPendingHoldersRow() throws Exception {
    PageResponse<InventoryItemDto> rows = page(entry(true, true));
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(rows);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(rows);

    String html = render(get("/inventory/material/" + materialId).param("fragment", "results"));

    assertThat(html).containsOnlyOnce(CHIP);
  }

  /**
   * Answers the backend reads whose path contains a fragment with a body, every other read empty.
   *
   * @param pathFragment the path fragment
   * @param body the body
   */
  private void stub(String pathFragment, Object body) {
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(inv -> {
          String url = inv.getArgument(0);
          return url.contains(pathFragment) ? body : Collections.emptyList();
        });
  }

  /**
   * Renders a request as the page's HTML.
   *
   * @param request the request
   * @return the rendered HTML
   * @throws Exception when the request fails
   */
  private String render(org.springframework.test.web.servlet.RequestBuilder request)
      throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The drill-down request of the test's stack.
   *
   * @return the request
   */
  private org.springframework.test.web.servlet.RequestBuilder entriesRequest() {
    return get("/inventory/all/stack/entries")
        .param("materialId", materialId.toString())
        .param("userId", userId.toString())
        .param("locationId", locationId.toString());
  }

  /**
   * One stack of the test's holder.
   *
   * @param relocationPending whether the holder's relocation is pending
   * @return the stack
   */
  private InventoryStackDto stack(boolean relocationPending) {
    return new InventoryStackDto(
        holder(),
        new LocationReferenceDto(locationId, "Port Olisar"),
        700,
        false,
        false,
        null,
        10.0,
        700.0,
        700,
        1,
        relocationPending);
  }

  /**
   * One material group around stacks.
   *
   * @param stacks the stacks
   * @return the group
   */
  private GroupedInventoryDto group(InventoryStackDto... stacks) {
    return new GroupedInventoryDto(
        new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
        null,
        10.0,
        700.0,
        700,
        List.of(stacks));
  }

  /**
   * One row of the test's holder.
   *
   * @param relocationPending whether a pending relocation locks the row
   * @param relocationLocked whether the lock refuses the caller
   * @return the row
   */
  private InventoryItemDto entry(boolean relocationPending, boolean relocationLocked) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        holder(),
        new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
        null,
        new LocationReferenceDto(locationId, "Port Olisar"),
        700,
        10.0,
        false,
        false,
        List.of(),
        10.0,
        List.of(),
        10.0,
        List.of(),
        null,
        null,
        3L,
        true,
        Instant.parse("2026-09-01T10:00:00Z"),
        relocationPending,
        relocationLocked);
  }

  /**
   * One page around a row.
   *
   * @param row the row
   * @return the page
   */
  private static PageResponse<InventoryItemDto> page(InventoryItemDto row) {
    return new PageResponse<>(List.of(row), 0, 20, 1, 1, Collections.emptyList());
  }

  /**
   * The test's holder.
   *
   * @return the holder's reference
   */
  private UserReferenceDto holder() {
    return new UserReferenceDto(userId, "tester", "Tester", "Tester", null);
  }
}
