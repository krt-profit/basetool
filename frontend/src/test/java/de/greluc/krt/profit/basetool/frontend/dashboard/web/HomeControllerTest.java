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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

class HomeControllerTest {

  private static final java.util.UUID ANNOUNCEMENT_ID = java.util.UUID.randomUUID();

  @Test
  void home_ShouldUsePreferredUsername_InsteadOfFullName() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    HomeController controller = new HomeController(new DashboardBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    HttpSession session = mock(HttpSession.class);
    OidcUser user = mock(OidcUser.class);

    when(user.getFullName()).thenReturn("Max Mustermann");
    when(user.getPreferredUsername()).thenReturn("max_muster");
    doReturn(Collections.emptyList()).when(user).getAuthorities();

    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    String view = controller.home(model, user, request);

    assertEquals("index", view);
    assertEquals("max_muster", model.getAttribute("username"));
  }

  @Test
  void markAnnouncementAsReadAjax_success_returns200() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    HomeController controller = new HomeController(new DashboardBackendClient(backendApiClient));

    var response = controller.markAnnouncementAsReadAjax(ANNOUNCEMENT_ID);

    assertEquals(200, response.getStatusCode().value());
    verify(backendApiClient)
        .put("/api/v1/users/me/read-announcement/{id}", null, Void.class, ANNOUNCEMENT_ID);
  }

  @Test
  void markAnnouncementAsReadAjax_backendFailure_returns502AndDoesNotThrow() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    HomeController controller = new HomeController(new DashboardBackendClient(backendApiClient));
    doThrow(new RuntimeException("backend down"))
        .when(backendApiClient)
        .put(anyString(), any(), any(), any(Object[].class));

    var response = controller.markAnnouncementAsReadAjax(ANNOUNCEMENT_ID);

    assertEquals(502, response.getStatusCode().value());
  }
}
