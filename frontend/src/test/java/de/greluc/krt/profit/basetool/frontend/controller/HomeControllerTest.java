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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

class HomeControllerTest {

  @Test
  void home_ShouldUsePreferredUsername_InsteadOfFullName() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    HomeController controller = new HomeController(backendApiClient);
    Model model = new ConcurrentModel();
    HttpSession session = mock(HttpSession.class);
    OidcUser user = mock(OidcUser.class);

    // Setup User
    when(user.getFullName()).thenReturn("Max Mustermann");
    when(user.getPreferredUsername()).thenReturn("max_muster");
    // Mock Authorities for admin check
    doReturn(Collections.emptyList()).when(user).getAuthorities();

    // Act
    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    String view = controller.home(model, user, request);

    // Assert
    assertEquals("index", view);
    // This assertion expects the CHANGE to be made. Currently it would fail (expecting "Max
    // Mustermann").
    // I will assert "max_muster" to verify my fix later.
    assertEquals("max_muster", model.getAttribute("username"));
  }

  @Test
  void markAnnouncementAsReadAjax_success_returns200() {
    // Arrange — the in-place twin (epic #571) marks the announcement read and answers 200 so the
    // home page removes the control without reloading.
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    HomeController controller = new HomeController(backendApiClient);

    // Act
    var response = controller.markAnnouncementAsReadAjax("ann-1");

    // Assert
    assertEquals(200, response.getStatusCode().value());
    verify(backendApiClient).put("/api/v1/users/me/read-announcement/ann-1", null, Void.class);
  }

  @Test
  void markAnnouncementAsReadAjax_backendFailure_returns502AndDoesNotThrow() {
    // Arrange — a failed mark-as-read must never break the page; the twin swallows the error and
    // answers 502 so the client simply leaves the control in place.
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    HomeController controller = new HomeController(backendApiClient);
    doThrow(new RuntimeException("backend down"))
        .when(backendApiClient)
        .put(anyString(), any(), any());

    // Act
    var response = controller.markAnnouncementAsReadAjax("ann-1");

    // Assert
    assertEquals(502, response.getStatusCode().value());
  }
}
