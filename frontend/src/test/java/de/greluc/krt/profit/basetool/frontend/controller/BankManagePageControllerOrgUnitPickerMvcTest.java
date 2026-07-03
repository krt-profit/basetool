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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * Regression test for the "create-account modal's Org-Einheit picker renders <code>null</code> for
 * every option" bug. The {@code /api/v1/org-units/active} endpoint returns the {@link
 * OrgUnitMembershipOptionDto} wire shape ({@code orgUnitId} / {@code orgUnitName} / {@code
 * orgUnitShorthand}); the page controller previously deserialized it into the unrelated {@code
 * OrgUnitReferenceDto} ({@code id} / {@code name} / {@code shorthand}), so Jackson left every label
 * field null and the dropdown printed {@code null} for each org unit. This test pins that an
 * org-unit option reaches the rendered create-account modal with its name (and shorthand) as the
 * visible label and its id as the option value.
 */
@SpringBootTest
class BankManagePageControllerOrgUnitPickerMvcTest {

  // Epic #692 Phase 6 (REQ-ORG-019): the bank create form sources its picker from the all-kinds
  // endpoint (Staffel + SK + Bereich + OL), so it can link AREA→Bereich and CARTEL→OL.
  private static final String ACTIVE_URI = "/api/v1/org-units/active-all-kinds";

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

  @Test
  @WithMockUser(roles = {"BANK_MANAGEMENT"})
  void manage_orgUnitOption_rendersNameAndIdNotNull() throws Exception {
    UUID orgUnitId = UUID.randomUUID();
    OrgUnitMembershipOptionDto staffel =
        new OrgUnitMembershipOptionDto(orgUnitId, "Staffel IRIDIUM", "IRI", "SQUADRON", true);

    // The accounts / holders / users fetches may return null; the controller defaults them to empty
    // lists. Only the org-unit catalog needs a concrete option to assert the picker label.
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);
    when(backendApiClient.get(eq(ACTIVE_URI), anyTypeRef())).thenReturn(List.of(staffel));

    mockMvc
        .perform(get("/bank/manage"))
        .andExpect(status().isOk())
        .andExpect(view().name("bank-manage"))
        // The visible label is the org-unit name plus its shorthand — never the literal "null".
        .andExpect(content().string(Matchers.containsString("Staffel IRIDIUM (IRI)")))
        // The option value is the org-unit id, so a submitted form actually carries an orgUnitId.
        .andExpect(content().string(Matchers.containsString("value=\"" + orgUnitId + "\"")))
        .andExpect(content().string(Matchers.not(Matchers.containsString(">null</option>"))));
  }
}
