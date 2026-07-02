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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserProxyControllerTest {

  @Test
  void searchUsers_ShouldCallWebClient() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);

    PageResponse<Map<String, Object>> mockPageResponse =
        new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 0, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(mockPageResponse);

    // Act
    List<Map<String, Object>> result = controller.searchUsers("query");

    // Assert
    assertNotNull(result);
    verify(backendApiClient)
        .get(eq("/api/v1/users/search?query=query&size=1000&sort=username,asc"), anyTypeRef());
  }
}
