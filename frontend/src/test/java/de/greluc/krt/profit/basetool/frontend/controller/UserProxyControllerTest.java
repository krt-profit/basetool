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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserProxyControllerTest {

  @Test
  void searchUsers_ShouldCallWebClient() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);

    PageResponse<Map<String, Object>> mockPageResponse =
        new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 0, Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/users/search/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq("query")))
        .thenReturn(mockPageResponse);

    List<Map<String, Object>> result = controller.searchUsers("query");

    assertNotNull(result);
    verify(backendApiClient)
        .get(
            eq("/api/v1/users/search/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq("query"));
  }

  @Test
  void searchUsers_passesMultiWordQueryAsUriVariable() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);

    PageResponse<Map<String, Object>> mockPageResponse =
        new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 0, Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/users/search/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq("John Doe")))
        .thenReturn(mockPageResponse);

    List<Map<String, Object>> result = controller.searchUsers("John Doe");

    assertNotNull(result);
    verify(backendApiClient)
        .get(
            eq("/api/v1/users/search/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq("John Doe"));
  }

  @Test
  void searchUsers_NullQuery_ForwardsEmptyMatchAllFilter() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);

    PageResponse<Map<String, Object>> mockPageResponse =
        new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 0, Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/users/search/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq("")))
        .thenReturn(mockPageResponse);

    List<Map<String, Object>> result = controller.searchUsers(null);

    assertNotNull(result);
    verify(backendApiClient)
        .get(
            eq("/api/v1/users/search/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq(""));
  }

  @Test
  void searchUsersForBank_ShouldCallBankSearchEndpoint() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);

    PageResponse<Map<String, Object>> mockPageResponse =
        new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 0, Collections.emptyList());
    when(backendApiClient.get(
            eq("/api/v1/users/search-bank/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq("query")))
        .thenReturn(mockPageResponse);

    List<Map<String, Object>> result = controller.searchUsersForBank("query");

    assertNotNull(result);
    verify(backendApiClient)
        .get(
            eq("/api/v1/users/search-bank/references?size=51&sort=username,asc&query={query}"),
            anyTypeRef(),
            eq("query"));
  }

  @Test
  void bothUserSearches_fetchOneRowPastTheRenderCap() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);

    controller.searchUsers("a");
    controller.searchUsersForBank("a");

    ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient, times(2)).get(uri.capture(), anyTypeRef(), eq("a"));
    assertEquals(PickerSearch.RENDER_CAP + 1, PickerSearch.PAGE_SIZE);
    for (String captured : uri.getAllValues()) {
      assertTrue(
          captured.contains("?size=" + PickerSearch.PAGE_SIZE + "&"),
          "page size must be PickerSearch.PAGE_SIZE: " + captured);
    }
  }

  @Test
  void userMemberships_ShouldForwardAllKindsTrueToBackend() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000044");
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenReturn(List.of(Map.of("orgUnitId", "u1", "orgUnitName", "IRIDIUM")));

    List<Map<String, Object>> result = controller.userMemberships(id, true);

    assertNotNull(result);
    verify(backendApiClient)
        .get(eq("/api/v1/users/" + id + "/memberships?allKinds=true"), anyTypeRef());
  }

  @Test
  void userMemberships_ShouldForwardAllKindsFalseByDefault() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000045");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(List.of());

    List<Map<String, Object>> result = controller.userMemberships(id, false);

    assertNotNull(result);
    verify(backendApiClient)
        .get(eq("/api/v1/users/" + id + "/memberships?allKinds=false"), anyTypeRef());
  }

  @Test
  void userMemberships_ShouldReturnEmptyListOnNullBackendResponse() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000046");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(null);

    List<Map<String, Object>> result = controller.userMemberships(id, true);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void getUser_ShouldResolveSingleUserById() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
    Map<String, Object> user = Map.of("id", id.toString(), "effectiveName", "Alice");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(user);

    Map<String, Object> result = controller.getUser(id);

    assertNotNull(result);
    verify(backendApiClient).get(eq("/api/v1/users/" + id), anyTypeRef());
  }

  @Test
  void getUser_ShouldReturnNullOnFailure() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    UserProxyController controller = new UserProxyController(backendApiClient);
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000043");
    when(backendApiClient.get(anyString(), anyTypeRef())).thenThrow(new RuntimeException("boom"));

    assertNull(controller.getUser(id));
  }
}
