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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.DataExportReportService;
import de.greluc.krt.profit.basetool.backend.service.DataExportService;
import java.time.Instant;
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
 * MockMvc gate matrix for the data export surfaces (REQ-SEC-058).
 *
 * <ul>
 *   <li>{@code /api/v1/users/me/export} is open to every authenticated member and accepts no user
 *       id.
 *   <li>{@code /api/v1/admin/users/&#123;id&#125;/export} is ADMIN-only at the URL matcher and the
 *       method gate.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class DataExportControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private DataExportService dataExportService;
  @MockitoBean private DataExportReportService dataExportReportService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(dataExportService.export(any()))
        .thenReturn(
            new DataExportService.DataExport(
                UUID.randomUUID(), "handle", Instant.now(), false, List.of(), List.of()));
    when(dataExportReportService.renderPdf(any())).thenReturn(new byte[] {1, 2, 3});
  }

  @Test
  void selfExport_member_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/me/export")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  void selfExportPdf_member_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/users/me/export/pdf")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  void selfExport_anonymous_isUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/export")).andExpect(status().isUnauthorized());
  }

  @Test
  void adminExport_member_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}/export", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());

    verify(dataExportService, never()).export(any());
  }

  @Test
  void adminExport_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}/export", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());

    verify(dataExportService, never()).export(any());
  }

  @Test
  void adminExportPdf_bankManagement_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}/export/pdf", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isForbidden());

    verify(dataExportReportService, never()).renderPdf(any());
  }

  @Test
  void adminExport_admin_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/users/{id}/export", UUID.randomUUID())
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }
}
