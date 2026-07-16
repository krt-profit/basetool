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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class AdminMissionDataPageControllerTest {

  @Test
  void listData_ShouldSortListsAscendingByName() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMissionDataPageController controller =
        new AdminMissionDataPageController(backendApiClient, new ParallelPageLoader());
    Model model = new ConcurrentModel();

    // Data for JobTypes (A, C, B) -> Expected (A, B, C)
    List<Map<String, Object>> jobTypes = new ArrayList<>();
    jobTypes.add(Map.of("name", "Alpha"));
    jobTypes.add(Map.of("name", "Charlie"));
    jobTypes.add(Map.of("name", "Bravo"));

    // Data for Squadrons (X, Z, Y) -> Expected (X, Y, Z)
    List<Map<String, Object>> squadrons = new ArrayList<>();
    squadrons.add(Map.of("name", "X-Ray"));
    squadrons.add(Map.of("name", "Zulu"));
    squadrons.add(Map.of("name", "Yankee"));

    PageResponse<Map<String, Object>> jobTypesPage =
        new PageResponse<>(jobTypes, 0, 1000, jobTypes.size(), 1, List.of("name,asc"));
    PageResponse<Map<String, Object>> squadronsPage =
        new PageResponse<>(squadrons, 0, 1000, squadrons.size(), 1, List.of("name,asc"));

    when(backendApiClient.get(
            eq("/api/v1/job-types?size=1000&sort=name,asc&includeInactive=false&page=0"),
            anyTypeRef()))
        .thenReturn(jobTypesPage);

    when(backendApiClient.get(
            eq("/api/v1/squadrons?size=1000&sort=name,asc&includeInactive=false&page=0"),
            anyTypeRef()))
        .thenReturn(squadronsPage);

    // Act
    controller.listData(false, false, false, null, model);

    // Assert
    @SuppressWarnings("unchecked")
    List<JobTypeDto> sortedJobTypes = (List<JobTypeDto>) model.getAttribute("jobTypes");
    assertEquals("Alpha", sortedJobTypes.get(0).name());
    assertEquals("Bravo", sortedJobTypes.get(1).name());
    assertEquals("Charlie", sortedJobTypes.get(2).name());

    @SuppressWarnings("unchecked")
    List<SquadronDto> sortedSquadrons = (List<SquadronDto>) model.getAttribute("squadrons");
    assertEquals("X-Ray", sortedSquadrons.get(0).name());
    assertEquals("Yankee", sortedSquadrons.get(1).name());
    assertEquals("Zulu", sortedSquadrons.get(2).name());
    assertEquals(Boolean.FALSE, model.getAttribute("jobTypesTruncated"));
    assertEquals(Boolean.FALSE, model.getAttribute("squadronsTruncated"));
    assertEquals(Boolean.FALSE, model.getAttribute("frequencyTypesTruncated"));
  }

  // covers REQ-ADMIN-001 — job types beyond the first backend page stay visible and editable
  @Test
  void listData_concatenatesAllJobTypePages() {
    // Arrange — the job-type catalog spans two backend pages
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMissionDataPageController controller =
        new AdminMissionDataPageController(backendApiClient, new ParallelPageLoader());
    Model model = new ConcurrentModel();

    PageResponse<Map<String, Object>> firstPage =
        new PageResponse<>(
            List.of(Map.of("name", "Zerspaner"), Map.of("name", "Aufklaerer")),
            0,
            1000,
            3,
            2,
            List.of("name,asc"));
    PageResponse<Map<String, Object>> secondPage =
        new PageResponse<>(List.of(Map.of("name", "Miner")), 1, 1000, 3, 2, List.of("name,asc"));
    String base = "/api/v1/job-types?size=1000&sort=name,asc&includeInactive=false";
    when(backendApiClient.get(eq(base + "&page=0"), anyTypeRef())).thenReturn(firstPage);
    when(backendApiClient.get(eq(base + "&page=1"), anyTypeRef())).thenReturn(secondPage);

    // Act
    controller.listData(false, false, false, null, model);

    // Assert — all three job types render, sorted, and nothing was flagged truncated
    @SuppressWarnings("unchecked")
    List<JobTypeDto> jobTypes = (List<JobTypeDto>) model.getAttribute("jobTypes");
    assertEquals(3, jobTypes.size(), "the second backend page must not be dropped");
    assertEquals("Aufklaerer", jobTypes.get(0).name());
    assertEquals("Miner", jobTypes.get(1).name());
    assertEquals("Zerspaner", jobTypes.get(2).name());
    assertEquals(Boolean.FALSE, model.getAttribute("jobTypesTruncated"));
  }

  // covers REQ-ADMIN-002 — a capped walk raises the per-section warning flags, never staying
  // silent
  @Test
  void listData_capHit_setsPerSectionTruncatedFlags() {
    // Arrange — every catalog reports more pages than the safety cap allows
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMissionDataPageController controller =
        new AdminMissionDataPageController(backendApiClient, new ParallelPageLoader());
    Model model = new ConcurrentModel();

    int reportedPages =
        de.greluc.krt.profit.basetool.frontend.support.CatalogPages.MAX_CATALOG_PAGES + 1;
    PageResponse<Map<String, Object>> endlessPage =
        new PageResponse<>(
            List.of(Map.of("name", "Row")), 0, 1000, reportedPages, reportedPages, List.of());
    when(backendApiClient.get(org.mockito.ArgumentMatchers.anyString(), anyTypeRef()))
        .thenReturn(endlessPage);

    // Act
    controller.listData(false, false, false, null, model);

    // Assert — each section carries its own truncation flag for its in-fragment banner
    assertEquals(Boolean.TRUE, model.getAttribute("jobTypesTruncated"));
    assertEquals(Boolean.TRUE, model.getAttribute("squadronsTruncated"));
    assertEquals(Boolean.TRUE, model.getAttribute("frequencyTypesTruncated"));
  }
}
