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
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.ArrayList;
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
 * MVC render checks for the {@code /notifications} inbox page's no-silent-cap UI (REQ-NOTIF-019).
 * The page shows the newest 50 notifications; when more exist it must render the "latest N of M"
 * hint and a load-more control, and when they all fit one page it must render neither. This test
 * also guards the Thymeleaf template itself — its {@code #{notifications.showingLatest(...)}}
 * MessageFormat call and the load-more fragment are exercised nowhere else.
 */
@SpringBootTest
class NotificationPageRenderMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Builds a backend notifications page with {@code count} rows and the given paging math. */
  private static PageResponse<NotificationDto> backendPage(
      int count, long totalElements, int totalPages) {
    List<NotificationDto> content = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      content.add(
          new NotificationDto(
              UUID.randomUUID(),
              "JOB_ORDER_CREATED",
              null,
              null,
              null,
              false,
              null,
              0L,
              Instant.parse("2026-01-01T00:00:00Z"),
              null));
    }
    return new PageResponse<>(content, 0, 50, totalElements, totalPages, List.of());
  }

  // covers REQ-NOTIF-019 — an inbox with more than one page renders the truncation hint + the
  // load-more control, so the newest-50 cap is visible, never silent. Also proves the template's
  // showingLatest(...) MessageFormat call and the load-more fragment render without error.
  @Test
  @WithMockUser
  void page_withMoreThanOnePage_rendersHintAndLoadMore() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(backendPage(50, 123, 3));

    mockMvc
        .perform(get("/notifications"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-notif-load-more")))
        .andExpect(content().string(containsString("notification-page-more")))
        .andExpect(content().string(containsString("data-notif-next-page=\"1\"")));
  }

  // covers REQ-NOTIF-019 — an inbox that fits one page renders neither the hint nor the load-more.
  @Test
  @WithMockUser
  void page_withSinglePage_rendersNoLoadMore() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(backendPage(7, 7, 1));

    mockMvc
        .perform(get("/notifications"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("data-notif-load-more"))));
  }
}
