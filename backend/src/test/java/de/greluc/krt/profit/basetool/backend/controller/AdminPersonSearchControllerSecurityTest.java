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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.PersonSearchService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc gate matrix for the admin-only Personensuche (REQ-SEC-060), including that a served
 * search reaches the seam that records it. The recorded payload is covered by {@link
 * de.greluc.krt.profit.basetool.backend.service.PersonSearchServiceAuditTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminPersonSearchControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private PersonSearchService personSearchService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void personSearch_member_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/person-search")
                .param("q", "somebody")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());

    verify(personSearchService, never()).search(any());
  }

  @Test
  void personSearch_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/person-search")
                .param("q", "somebody")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());

    verify(personSearchService, never()).search(any());
  }

  @Test
  void personSearch_bankManagement_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/person-search")
                .param("q", "somebody")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isForbidden());

    verify(personSearchService, never()).search(any());
  }

  @Test
  void personSearch_admin_isAllowed() throws Exception {
    when(personSearchService.search(any()))
        .thenReturn(new PersonSearchService.PersonSearchResult(List.of(), false, List.of()));

    mockMvc
        .perform(
            get("/api/v1/admin/person-search")
                .param("q", "somebody")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void personSearch_isHandedToTheAuditingSeam() throws Exception {
    PersonSearchService.PersonSearchResult result =
        new PersonSearchService.PersonSearchResult(List.of(), false, List.of());
    when(personSearchService.search(any())).thenReturn(result);

    mockMvc
        .perform(
            get("/api/v1/admin/person-search")
                .param("q", "SomeVeryDistinctiveHandle")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());

    verify(personSearchService).recordSearch(eq("SomeVeryDistinctiveHandle"), eq(result));
  }
}
