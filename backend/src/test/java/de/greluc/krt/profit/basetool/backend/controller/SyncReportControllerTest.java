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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.ExternalSyncReport;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import de.greluc.krt.profit.basetool.backend.testcontext.LeafServiceMockTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Security-gate tests for {@link SyncReportController}: the read endpoint is admin-only. The
 * service is a {@link LeafServiceMockTest} mock, so the test exercises the
 * {@code @PreAuthorize("hasRole('ADMIN')")} gate without TestContainers data setup.
 */
@LeafServiceMockTest
@Transactional
class SyncReportControllerTest {

  private static final String BASE = "/api/v1/sync-reports";

  @Autowired private WebApplicationContext context;

  @Autowired private SyncReportService syncReportService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    Page<ExternalSyncReport> empty = new PageImpl<>(List.of());
    when(syncReportService.findEvents(any(), any())).thenReturn(empty);
  }

  @Test
  void listEvents_forbiddenForNonAdmin() throws Exception {
    mockMvc
        .perform(get(BASE).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void listEvents_allowedForAdmin() throws Exception {
    mockMvc
        .perform(
            get(BASE)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void listEvents_withSourceFilter_allowedForAdmin() throws Exception {
    mockMvc
        .perform(
            get(BASE + "?source=SCWIKI&page=0&size=25")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void deleteOldEvents_forbiddenForNonAdmin() throws Exception {
    mockMvc
        .perform(
            delete(BASE + "?olderThanDays=30")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteOldEvents_allowedForAdmin_relaysSourceAndDaysToService() throws Exception {
    when(syncReportService.deleteOlderThan(eq(SyncSourceSystem.UEX), eq(30))).thenReturn(4);

    mockMvc
        .perform(
            delete(BASE + "?source=UEX&olderThanDays=30")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    verify(syncReportService).deleteOlderThan(eq(SyncSourceSystem.UEX), eq(30));
  }
}
