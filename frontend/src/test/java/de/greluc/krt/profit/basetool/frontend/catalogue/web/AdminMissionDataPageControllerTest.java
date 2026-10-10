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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class AdminMissionDataPageControllerTest {

  @Test
  void listData_ShouldSortListsAscendingByName() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMissionDataPageController controller =
        new AdminMissionDataPageController(
            new CatalogueBackendClient(backendApiClient),
            new ParallelPageLoader(),
            new CatalogueCacheEviction(backendApiClient));
    Model model = new ConcurrentModel();

    List<JobTypeDto> jobTypes = new ArrayList<>();
    jobTypes.add(jobType("Alpha"));
    jobTypes.add(jobType("Charlie"));
    jobTypes.add(jobType("Bravo"));

    List<SquadronDto> squadrons = new ArrayList<>();
    squadrons.add(squadron("X-Ray"));
    squadrons.add(squadron("Zulu"));
    squadrons.add(squadron("Yankee"));

    PageResponse<JobTypeDto> jobTypesPage =
        new PageResponse<>(jobTypes, 0, 1000, jobTypes.size(), 1, List.of("name,asc"));
    PageResponse<SquadronDto> squadronsPage =
        new PageResponse<>(squadrons, 0, 1000, squadrons.size(), 1, List.of("name,asc"));

    when(backendApiClient.get(
            eq(
                "/api/v1/job-types?size=1000&sort=name,asc"
                    + "&includeInactive={includeInactive}&page={page}"),
            anyTypeRef(),
            eq(false),
            eq(0)))
        .thenReturn(jobTypesPage);

    when(backendApiClient.get(
            eq(
                "/api/v1/squadrons?size=1000&sort=name,asc"
                    + "&includeInactive={includeInactive}&page={page}"),
            anyTypeRef(),
            eq(false),
            eq(0)))
        .thenReturn(squadronsPage);

    controller.listData(false, false, false, null, model);

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

  @Test
  void listData_concatenatesAllJobTypePages() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMissionDataPageController controller =
        new AdminMissionDataPageController(
            new CatalogueBackendClient(backendApiClient),
            new ParallelPageLoader(),
            new CatalogueCacheEviction(backendApiClient));
    Model model = new ConcurrentModel();

    PageResponse<JobTypeDto> firstPage =
        new PageResponse<>(
            List.of(jobType("Zerspaner"), jobType("Aufklaerer")),
            0,
            1000,
            3,
            2,
            List.of("name,asc"));
    PageResponse<JobTypeDto> secondPage =
        new PageResponse<>(List.of(jobType("Miner")), 1, 1000, 3, 2, List.of("name,asc"));
    String template =
        "/api/v1/job-types?size=1000&sort=name,asc&includeInactive={includeInactive}&page={page}";
    when(backendApiClient.get(eq(template), anyTypeRef(), eq(false), eq(0))).thenReturn(firstPage);
    when(backendApiClient.get(eq(template), anyTypeRef(), eq(false), eq(1))).thenReturn(secondPage);

    controller.listData(false, false, false, null, model);

    @SuppressWarnings("unchecked")
    List<JobTypeDto> jobTypes = (List<JobTypeDto>) model.getAttribute("jobTypes");
    assertEquals(3, jobTypes.size(), "the second backend page must not be dropped");
    assertEquals("Aufklaerer", jobTypes.get(0).name());
    assertEquals("Miner", jobTypes.get(1).name());
    assertEquals("Zerspaner", jobTypes.get(2).name());
    assertEquals(Boolean.FALSE, model.getAttribute("jobTypesTruncated"));
  }

  @Test
  void listData_capHit_setsPerSectionTruncatedFlags() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMissionDataPageController controller =
        new AdminMissionDataPageController(
            new CatalogueBackendClient(backendApiClient),
            new ParallelPageLoader(),
            new CatalogueCacheEviction(backendApiClient));
    Model model = new ConcurrentModel();

    int reportedPages =
        de.greluc.krt.profit.basetool.frontend.support.CatalogPages.MAX_CATALOG_PAGES + 1;
    PageResponse<Object> endlessPage =
        new PageResponse<>(
            List.of(jobType("Row")), 0, 1000, reportedPages, reportedPages, List.of());
    PageResponse<Object> endlessSquadrons =
        new PageResponse<>(
            List.of(squadron("Row")), 0, 1000, reportedPages, reportedPages, List.of());
    PageResponse<Object> endlessFrequencyTypes =
        new PageResponse<>(
            List.of(new FrequencyTypeDto(UUID.randomUUID(), "Row", null, true, 0, 0L)),
            0,
            1000,
            reportedPages,
            reportedPages,
            List.of());
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.startsWith("/api/v1/job-types"),
            anyTypeRef(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(endlessPage);
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.startsWith("/api/v1/squadrons"),
            anyTypeRef(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(endlessSquadrons);
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.startsWith("/api/v1/frequency-types"),
            anyTypeRef(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(endlessFrequencyTypes);

    controller.listData(false, false, false, null, model);

    assertEquals(Boolean.TRUE, model.getAttribute("jobTypesTruncated"));
    assertEquals(Boolean.TRUE, model.getAttribute("squadronsTruncated"));
    assertEquals(Boolean.TRUE, model.getAttribute("frequencyTypesTruncated"));
  }

  private static JobTypeDto jobType(String name) {
    return new JobTypeDto(UUID.randomUUID(), name, null, "MISSION", null, true, false, false, 0L);
  }

  private static SquadronDto squadron(String name) {
    return new SquadronDto(UUID.randomUUID(), name, null, null, true, false, false, 0L);
  }
}
