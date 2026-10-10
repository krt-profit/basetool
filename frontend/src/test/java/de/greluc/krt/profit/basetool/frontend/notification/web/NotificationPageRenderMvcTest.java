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

package de.greluc.krt.profit.basetool.frontend.notification.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.notification.model.NotificationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * MVC render checks for the {@code /notifications} inbox (REQ-NOTIF-019): the "latest N of M" hint
 * and load-more control appear only when more notifications exist than fit one page.
 */
@SpringBootTest
class NotificationPageRenderMvcTest {

  /** The job order the linked notifications are about. */
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

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

  /**
   * Renders {@code /notifications} in German with the given backend rows on a single page.
   *
   * @param rows the rows the backend returns
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private String renderInbox(List<NotificationDto> rows) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(
            new PageResponse<>(rows, 0, 50, rows.size(), rows.isEmpty() ? 0 : 1, List.of()));
    return mockMvc
        .perform(get("/notifications").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * One job-order notification as the backend returns it.
   *
   * @param read whether it is read
   * @return the row
   */
  private static NotificationDto jobOrderNotification(boolean read) {
    return new NotificationDto(
        UUID.randomUUID(),
        "JOB_ORDER_CREATED",
        null,
        "JOB_ORDER",
        ORDER_ID,
        read,
        null,
        0L,
        Instant.parse("2026-01-01T00:00:00Z"),
        null);
  }

  /**
   * The inbox renders on pattern A: head with eyebrow and no primary action, the unread / all
   * segment, the list in a flush card with linked rows, and the empty states.
   */
  @Test
  @WithMockUser
  void page_rendersTheListPattern() throws Exception {
    String html = renderInbox(List.of(jobOrderNotification(false), jobOrderNotification(true)));

    assertTrue(html.contains("class=\"page-head\""), "page head");
    assertTrue(
        Pattern.compile("class=\"page-eyebrow\"[^>]*>Persönlich<").matcher(html).find(), "eyebrow");
    assertFalse(html.contains("class=\"greeting"), "no greeting banner");
    assertFalse(html.contains("hud-box"), "no hud-box");
    assertFalse(html.contains("btn--cta"), "no primary action");
    assertTrue(html.contains("data-notif-mark-all"));
    assertTrue(html.contains("data-overflow-menu"), "clear-read sits in the overflow menu");
    assertTrue(html.contains("data-notif-clear-read"));
    assertTrue(html.contains("data-testid=\"segment-filter-unread\""));
    assertTrue(html.contains("data-testid=\"segment-filter-all\""));
    assertTrue(
        Pattern.compile("name=\"filter\" value=\"UNREAD\" checked=\"checked\"")
            .matcher(html)
            .find(),
        "unread is the default view");
    assertTrue(html.contains("class=\"card card--flush notification-inbox\""));
    assertTrue(html.contains("data-notif-filter=\"unread\""));
    assertTrue(html.contains("id=\"notification-page-list\""));
    assertTrue(html.contains("data-notif-read=\"false\""));
    assertTrue(html.contains("data-notif-read=\"true\""));
    assertTrue(
        Pattern.compile("class=\"row-link\"[^>]*href=\"/orders/" + ORDER_ID + "\"")
            .matcher(html)
            .find(),
        "a job-order notification links its order");
    assertTrue(html.contains("data-testid=\"notifications-unread-empty\""));
    assertTrue(
        Pattern.compile("data-notif-empty[^>]*notification-badge-hidden").matcher(html).find(),
        "the empty state is hidden while rows exist");
  }

  /** An empty inbox shows the empty state. */
  @Test
  @WithMockUser
  void page_withoutNotifications_showsTheEmptyState() throws Exception {
    String html = renderInbox(List.of());

    assertTrue(html.contains("data-testid=\"empty-state\""));
    assertTrue(html.contains("Keine Benachrichtigungen"));
    assertFalse(
        Pattern.compile("data-notif-empty[^>]*notification-badge-hidden").matcher(html).find(),
        "the empty state is visible");
    assertFalse(html.contains("class=\"notification-item"));
  }
}
