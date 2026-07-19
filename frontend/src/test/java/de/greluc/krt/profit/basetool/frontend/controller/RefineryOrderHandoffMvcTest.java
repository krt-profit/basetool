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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Behavioural guard for the prefetch-safe one-click ingest handoff (REQ-INGEST-004, ADR-0110): the
 * navigational {@code GET /refinery-orders/create?handoff=<id>} must render the empty form and
 * <strong>never</strong> consume the single-use pickup (so a browser prefetch / duplicate top-level
 * load cannot burn the token — the 2026-07-19 Firefox double-GET incident), while the
 * script-initiated {@code POST /refinery-orders/import-handoff} performs the one-time consume and
 * returns the pre-filled {@code refineryImportFormBody} fragment (a miss degrades to the fresh form
 * plus the {@code ingest.handoff.notFound} inline notice). The end-to-end pre-fill via the in-place
 * swap is covered by {@code IngestHandoffE2eTest}; this pins the controller contract through a full
 * template render.
 */
@SpringBootTest
class RefineryOrderHandoffMvcTest {

  /** A UUID subject so {@code getCurrentUserId} parses it directly without a backend round-trip. */
  private static final UUID SUB = UUID.randomUUID();

  private static final UUID MATERIAL_ID = UUID.randomUUID();

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;
  @MockitoBean private IngestHandoffService ingestHandoffService;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    MaterialDto raw =
        new MaterialDto(
            MATERIAL_ID,
            "Stileron (Raw)",
            "RAW",
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    PageResponse<MaterialDto> materials =
        new PageResponse<>(List.of(raw), 0, 1000, 1, 1, Collections.emptyList());
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS), anyTypeRef(), eq(true)))
        .thenReturn(materials);
  }

  @Test
  void createPageGetWithHandoff_doesNotConsume_andCarriesPendingHandoffId() throws Exception {
    mockMvc
        .perform(
            get("/refinery-orders/create")
                .param("handoff", "tQfTskHGirAhVIpYj8BiDP876m0")
                .with(oidcLogin().idToken(token -> token.subject(SUB.toString()))))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create"))
        // The id is threaded to the page module, which will POST it to /import-handoff.
        .andExpect(model().attribute("pendingHandoffId", "tQfTskHGirAhVIpYj8BiDP876m0"))
        // A safe navigation must not show the not-found notice and must not have consumed anything.
        .andExpect(model().attributeDoesNotExist("importErrorKey"));

    // The crux of the fix: the navigational GET never touches the single-use Redis pickup, so a
    // prefetch or a duplicate top-level load cannot burn it.
    verify(ingestHandoffService, never()).consume(any(), any(), any(), any());
  }

  @Test
  void importHandoffPost_onMiss_rendersFragmentWithNotFoundNotice() throws Exception {
    when(ingestHandoffService.consume(
            eq(SUB.toString()),
            eq("expired-or-foreign-id"),
            eq(HandoffKind.REFINERY),
            eq(RefineryImportDraftDto.class)))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/refinery-orders/import-handoff")
                .param("handoff", "expired-or-foreign-id")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin().idToken(token -> token.subject(SUB.toString())))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create :: refineryImportFormBody"))
        .andExpect(model().attribute("importErrorKey", "ingest.handoff.notFound"))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-error\"")));

    verify(ingestHandoffService, times(1))
        .consume(
            eq(SUB.toString()),
            eq("expired-or-foreign-id"),
            eq(HandoffKind.REFINERY),
            eq(RefineryImportDraftDto.class));
  }

  @Test
  void importHandoffPost_onHit_consumesOnceAndRendersPrefilledFragmentBanner() throws Exception {
    RefineryOrderDto order =
        new RefineryOrderDto(
            null, null, null, null, null, null, null, null, null, null, null, List.of(), null, null,
            null, null);
    RefineryImportDraftDto draft = new RefineryImportDraftDto(order, List.of(), 1, 2, 0);
    when(ingestHandoffService.consume(
            eq(SUB.toString()),
            eq("valid-id"),
            eq(HandoffKind.REFINERY),
            eq(RefineryImportDraftDto.class)))
        .thenReturn(Optional.of(draft));

    mockMvc
        .perform(
            post("/refinery-orders/import-handoff")
                .param("handoff", "valid-id")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(oidcLogin().idToken(token -> token.subject(SUB.toString())))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-create :: refineryImportFormBody"))
        // The review banner renders because the counters (importGoodsTotal) are present.
        .andExpect(model().attribute("importGoodsTotal", 2))
        .andExpect(model().attributeDoesNotExist("importErrorKey"))
        .andExpect(content().string(containsString("data-testid=\"refinery-import-banner\"")));

    verify(ingestHandoffService, times(1))
        .consume(
            eq(SUB.toString()),
            eq("valid-id"),
            eq(HandoffKind.REFINERY),
            eq(RefineryImportDraftDto.class));
  }
}
