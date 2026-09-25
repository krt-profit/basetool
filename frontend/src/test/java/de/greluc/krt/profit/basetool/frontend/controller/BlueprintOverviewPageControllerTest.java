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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

/** Unit tests for {@link BlueprintOverviewPageController}: model population and owner relay. */
@ExtendWith(MockitoExtension.class)
class BlueprintOverviewPageControllerTest {

  @Mock private BackendApiClient backendApiClient;
  @InjectMocks private BlueprintOverviewPageController controller;

  @Test
  void view_populatesOverviewAndPageEnvelope_withDefaults() {
    PageResponse<BlueprintOverviewEntryDto> page =
        new PageResponse<>(
            List.of(new BlueprintOverviewEntryDto("aurora", "Aurora MR", 3L)),
            0,
            50,
            1,
            1,
            List.of());
    when(backendApiClient.get(
            contains("/api/v1/personal-blueprints/overview?page=0&size=50"), anyTypeRef()))
        .thenReturn(page);

    Model model = new ExtendedModelMap();
    String view = controller.view(null, null, null, null, model);

    assertEquals("blueprint-overview", view);
    Object overview = model.getAttribute("overview");
    assertTrue(overview instanceof List<?> && ((List<?>) overview).size() == 1);
    assertEquals(page, model.getAttribute("overviewPage"));
    assertEquals(List.of(10, 50, 100), model.getAttribute("pageSizes"));
  }

  @Test
  void view_nonWhitelistedSize_fallsBackToDefault() {
    when(backendApiClient.get(contains("?page=2&size=50"), anyTypeRef()))
        .thenReturn(new PageResponse<BlueprintOverviewEntryDto>(List.of(), 2, 50, 0, 0, List.of()));

    controller.view(2, 1000, null, null, new ExtendedModelMap());

    verify(backendApiClient).get(contains("?page=2&size=50"), anyTypeRef());
  }

  @Test
  void view_search_isRelayedAsUriVariable_andEchoedTrimmed() {
    when(backendApiClient.get(contains("&search={search}"), anyTypeRef(), eq("Aurora")))
        .thenReturn(new PageResponse<BlueprintOverviewEntryDto>(List.of(), 0, 10, 0, 0, List.of()));

    Model model = new ExtendedModelMap();
    controller.view(0, 10, "  Aurora  ", null, model);

    verify(backendApiClient)
        .get(contains("?page=0&size=10&search={search}"), anyTypeRef(), eq("Aurora"));
    assertEquals("Aurora", model.getAttribute("search"));
  }

  @Test
  void view_fragmentResults_returnsResultsFragmentView() {
    when(backendApiClient.get(contains("?page=0&size=50"), anyTypeRef()))
        .thenReturn(new PageResponse<BlueprintOverviewEntryDto>(List.of(), 0, 50, 0, 0, List.of()));

    Model model = new ExtendedModelMap();
    String view = controller.view(null, null, null, "results", model);

    assertEquals("blueprint-overview :: results", view);
    assertEquals(List.of(10, 50, 100), model.getAttribute("pageSizes"));
  }

  @Test
  void view_onBackendError_setsErrorKey_andEmptyOverview() {
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenThrow(new RuntimeException("boom"));

    Model model = new ExtendedModelMap();
    String view = controller.view(null, null, null, null, model);

    assertEquals("blueprint-overview", view);
    assertEquals("error.blueprintOverview.load", model.getAttribute("error"));
    assertTrue(((List<?>) model.getAttribute("overview")).isEmpty());
  }

  @Test
  void owners_relaysProductKey_asUriVariable_andReturnsList() {
    when(backendApiClient.get(
            contains("/api/v1/personal-blueprints/overview/owners?productKey={productKey}"),
            anyTypeRef(),
            eq("aurora")))
        .thenReturn(List.of(new BlueprintOverviewOwnerDto("Alpha", true)));

    List<BlueprintOverviewOwnerDto> result = controller.owners("aurora");

    assertEquals(1, result.size());
    assertEquals("Alpha", result.get(0).ownerName());
  }

  @Test
  void owners_onBackendError_returnsEmptyList() {
    when(backendApiClient.get(any(String.class), anyTypeRef(), eq("aurora")))
        .thenThrow(new RuntimeException("boom"));

    assertTrue(controller.owners("aurora").isEmpty());
  }
}
