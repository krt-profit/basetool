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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.service.AuditReportService;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc gate matrix for the activity audit surface (REQ-AUDIT-001): {@code /api/v1/audit/**} is
 * admin-only at both the URL matcher and the method-level {@code @PreAuthorize}, so a non-admin —
 * even one that holds another elevated role — is forbidden on both the viewer and the export.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditAdminControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private AuditService auditService;
  @MockitoBean private AuditReportService auditReportService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void auditLog_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/INVENTORY")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditLog_bankEmployee_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/JOB_ORDER")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditLog_admin_isAllowed() throws Exception {
    when(auditService.getEvents(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Page.empty());
    mockMvc
        .perform(
            get("/api/v1/audit/REFINERY")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void auditLog_promotion_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/PROMOTION")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditLog_promotion_admin_isAllowed() throws Exception {
    when(auditService.getEvents(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Page.empty());
    mockMvc
        .perform(
            get("/api/v1/audit/PROMOTION")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void auditExport_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/INVENTORY/export")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditExport_admin_isAllowed() throws Exception {
    when(auditReportService.generateAuditLogPdf(any(), any(), any(), any()))
        .thenReturn(new byte[] {1, 2, 3});
    mockMvc
        .perform(
            get("/api/v1/audit/INVENTORY/export")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void auditExportJson_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/INVENTORY/export.json")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditExportJson_admin_isAllowed() throws Exception {
    when(auditReportService.generateAuditLogJson(any(), any(), any()))
        .thenReturn(java.util.List.of());
    mockMvc
        .perform(
            get("/api/v1/audit/JOB_ORDER/export.json")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void auditPurge_officer_isForbidden() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/audit/INVENTORY")
                .param("before", "2026-01-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditPurge_admin_isAllowed() throws Exception {
    when(auditService.purgeBefore(any(), any())).thenReturn(0);
    mockMvc
        .perform(
            delete("/api/v1/audit/INVENTORY")
                .param("before", "2026-01-01T00:00:00Z")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }
}
