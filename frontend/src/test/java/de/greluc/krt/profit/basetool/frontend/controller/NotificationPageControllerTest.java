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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationPageSliceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationViewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit tests for the {@code /notifications} inbox paging (REQ-NOTIF-019): the newest 50 are
 * rendered with the total count and a more-pages flag, and {@code /page-items} relays later pages.
 */
class NotificationPageControllerTest {

  /** Builds a controller whose only wired collaborator that matters here is the backend client. */
  private static NotificationPageController controllerWith(BackendApiClient backendApiClient) {
    MessageSource messageSource = mock(MessageSource.class);
    when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
        .thenReturn("Notification text");
    return new NotificationPageController(
        backendApiClient,
        messageSource,
        mock(WebClient.class),
        mock(OAuth2AuthorizedClientRepository.class),
        new SimpleMeterRegistry());
  }

  /** Builds a backend notifications page with {@code count} rows and the given paging math. */
  private static PageResponse<NotificationDto> backendPage(
      int count, int pageIndex, int size, long totalElements, int totalPages) {
    List<NotificationDto> content = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      content.add(
          new NotificationDto(
              java.util.UUID.randomUUID(),
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
    return new PageResponse<>(content, pageIndex, size, totalElements, totalPages, List.of());
  }

  @Test
  void page_exposesTotalAndHasMore_fromFirstBackendPage() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(backendPage(50, 0, 50, 123, 3));
    NotificationPageController controller = controllerWith(backendApiClient);

    Model model = new ExtendedModelMap();
    String view = controller.page(model);

    assertEquals("notifications", view);
    assertEquals(123L, model.getAttribute("notifTotal"));
    assertEquals(Boolean.TRUE, model.getAttribute("notifHasMore"));
    assertEquals(50, ((List<?>) model.getAttribute("notifications")).size());

    ArgumentCaptor<Object> pageArg = ArgumentCaptor.captor();
    ArgumentCaptor<Object> sizeArg = ArgumentCaptor.captor();
    verify(backendApiClient).get(anyString(), anyTypeRef(), pageArg.capture(), sizeArg.capture());
    assertEquals(0, pageArg.getValue());
    assertEquals(50, sizeArg.getValue());
  }

  @Test
  void page_singlePage_reportsNoMore() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(backendPage(7, 0, 50, 7, 1));
    NotificationPageController controller = controllerWith(backendApiClient);

    Model model = new ExtendedModelMap();
    controller.page(model);

    assertEquals(7L, model.getAttribute("notifTotal"));
    assertEquals(Boolean.FALSE, model.getAttribute("notifHasMore"));
  }

  @Test
  void page_backendReturnsNull_failsSoftToEmpty() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any())).thenReturn(null);
    NotificationPageController controller = controllerWith(backendApiClient);

    Model model = new ExtendedModelMap();
    String view = controller.page(model);

    assertEquals("notifications", view);
    assertTrue(((List<?>) model.getAttribute("notifications")).isEmpty());
    assertEquals(0L, model.getAttribute("notifTotal"));
    assertEquals(Boolean.FALSE, model.getAttribute("notifHasMore"));
  }

  @Test
  void pageItems_returnsLocalizedSlice_withHasMoreFlag() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(backendPage(50, 1, 50, 123, 3));
    NotificationPageController controller = controllerWith(backendApiClient);

    NotificationPageSliceDto slice = controller.pageItems(1);

    assertEquals(50, slice.items().size());
    assertEquals(123L, slice.totalElements());
    assertTrue(slice.hasMore(), "page 1 of 3 still has a further page");
    for (NotificationViewDto view : slice.items()) {
      assertEquals("Notification text", view.text());
    }

    ArgumentCaptor<Object> pageArg = ArgumentCaptor.captor();
    verify(backendApiClient).get(anyString(), anyTypeRef(), pageArg.capture(), any());
    assertEquals(1, pageArg.getValue());
  }

  @Test
  void pageItems_lastPage_reportsNoMore() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(backendPage(23, 2, 50, 123, 3));
    NotificationPageController controller = controllerWith(backendApiClient);

    NotificationPageSliceDto slice = controller.pageItems(2);

    assertFalse(slice.hasMore(), "page 2 of 3 is the last page");
    assertEquals(123L, slice.totalElements());
  }

  @Test
  void pageItems_negativePage_clampsToZero() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(), any()))
        .thenReturn(backendPage(50, 0, 50, 123, 3));
    NotificationPageController controller = controllerWith(backendApiClient);

    controller.pageItems(-5);

    ArgumentCaptor<Object> pageArg = ArgumentCaptor.captor();
    verify(backendApiClient).get(anyString(), anyTypeRef(), pageArg.capture(), any());
    assertEquals(0, pageArg.getValue());
  }
}
