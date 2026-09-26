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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.controller.AdminUexPageController.StarSystemGroup;
import de.greluc.krt.profit.basetool.frontend.model.dto.CityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OutpostDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpaceStationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Mockito tests for {@link AdminUexPageController}: the override dispatcher's backend URL per
 * kind/action, {@code listData}'s parsing and "latest UEX sync" header, and the hierarchy builder's
 * placement of terminals, including orphans.
 */
class AdminUexPageControllerTest {

  @Test
  void updateLoadingDockOverride_routesYesActionForCity() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    String view = controller.updateLoadingDockOverride("cities", id, "yes", attrs);

    assertEquals("redirect:/admin/uex-data", view);
    verify(client)
        .patch(eq("/api/v1/cities/" + id + "/loading-dock?value=true"), any(), eq(Void.class));
  }

  @Test
  void updateLoadingDockOverride_routesNoActionForSpaceStation() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    controller.updateLoadingDockOverride("space-stations", id, "no", attrs);

    verify(client)
        .patch(
            eq("/api/v1/space-stations/" + id + "/loading-dock?value=false"),
            any(),
            eq(Void.class));
  }

  @Test
  void updateLoadingDockOverride_routesUexActionForOutpost() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    controller.updateLoadingDockOverride("outposts", id, "uex", attrs);

    verify(client).delete("/api/v1/outposts/" + id + "/loading-dock-override", Void.class);
  }

  @Test
  void updateLoadingDockOverride_routesYesActionForPoi() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    controller.updateLoadingDockOverride("pois", id, "yes", attrs);

    verify(client)
        .patch(eq("/api/v1/pois/" + id + "/loading-dock?value=true"), any(), eq(Void.class));
  }

  @Test
  void updateLoadingDockOverride_routesYesActionForTerminal() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    controller.updateLoadingDockOverride("terminals", id, "yes", attrs);

    verify(client)
        .patch(eq("/api/v1/terminals/" + id + "/loading-dock?value=true"), any(), eq(Void.class));
  }

  @Test
  void updateLoadingDockOverride_rejectsUnknownKindWithoutBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    String view = controller.updateLoadingDockOverride("planets", id, "yes", attrs);

    assertEquals("redirect:/admin/uex-data", view);
    verify(client, never())
        .patch(ArgumentMatchers.<String>any(), any(), ArgumentMatchers.<Class<?>>any());
    verify(client, never())
        .delete(ArgumentMatchers.<String>any(), ArgumentMatchers.<Class<?>>any());
  }

  @Test
  void updateLoadingDockOverride_rejectsUnknownActionWithoutBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    controller.updateLoadingDockOverride("cities", id, "delete", attrs);

    verify(client, never())
        .patch(ArgumentMatchers.<String>any(), any(), ArgumentMatchers.<Class<?>>any());
    verify(client, never())
        .delete(ArgumentMatchers.<String>any(), ArgumentMatchers.<Class<?>>any());
  }

  @Test
  void updateTerminalAutoLoadOverride_yesPatchesTrue() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    controller.updateTerminalAutoLoadOverride(id, "yes", attrs);

    verify(client)
        .patch(eq("/api/v1/terminals/" + id + "/auto-load?value=true"), any(), eq(Void.class));
  }

  @Test
  void updateTerminalAutoLoadOverride_uexCallsDelete() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();

    controller.updateTerminalAutoLoadOverride(id, "uex", attrs);

    verify(client).delete("/api/v1/terminals/" + id + "/auto-load-override", Void.class);
  }

  @Test
  void toggleTerminalVisibility_evictsTerminalDomainAfterWrite() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    UUID id = UUID.randomUUID();
    when(client.get("/api/v1/terminals/" + id, TerminalDto.class))
        .thenReturn(terminalIn("Lorville TDD", "Stanton", "Lorville", null));

    String view = controller.toggleTerminalVisibility(id, true, attrs);

    assertEquals("redirect:/admin/uex-data", view);
    verify(client).put(eq("/api/v1/terminals/" + id), any(), eq(Void.class));
    verify(client).evict(CacheDomain.TERMINAL);
  }

  @Test
  void toggleTerminalVisibilityAjax_evictsTerminalDomainAfterWrite() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    UUID id = UUID.randomUUID();
    when(client.get("/api/v1/terminals/" + id, TerminalDto.class))
        .thenReturn(terminalIn("Area 18 TDD", "Stanton", "Area 18", null));

    ResponseEntity<Object> response = controller.toggleTerminalVisibilityAjax(id);

    assertTrue(
        response.getStatusCode().is2xxSuccessful(), "ajax toggle must return 2xx on success");
    verify(client).put(eq("/api/v1/terminals/" + id), any(), eq(Void.class));
    verify(client).evict(CacheDomain.TERMINAL);
  }

  @Test
  void listData_parsesUexMirrorFields_andComputesLatestSyncAttribute() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);

    Instant olderSync = Instant.parse("2026-05-16T08:00:00Z");
    Instant newerSync = Instant.parse("2026-05-16T12:30:00Z");

    Map<String, Object> termOlder = new HashMap<>();
    termOlder.put("id", UUID.randomUUID().toString());
    termOlder.put("name", "Lorville TDD");
    termOlder.put("starSystemName", "Stanton");
    termOlder.put("cityName", "Lorville");
    termOlder.put("uexSyncedAt", olderSync.toString());
    Map<String, Object> termNewer = new HashMap<>();
    termNewer.put("id", UUID.randomUUID().toString());
    termNewer.put("name", "Area 18 TDD");
    termNewer.put("starSystemName", "Stanton");
    termNewer.put("cityName", "Area 18");
    termNewer.put("uexSyncedAt", newerSync.toString());

    stubEmptyPage(client, "/api/v1/cities?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/space-stations?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/outposts?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/pois?size=10000&sort=name,asc");
    stubPage(client, "/api/v1/terminals?size=10000&sort=name,asc", termOlder, termNewer);

    ConcurrentModel model = new ConcurrentModel();
    String view = controller.listData(model);

    assertEquals("admin/uex", view);
    assertEquals(newerSync, model.getAttribute("latestUexSync"));
    assertEquals(2L, model.getAttribute("totalTerminals"));
  }

  @Test
  void listData_concatenatesTerminalPages_andDerivesTotalsFromTotalElements() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);

    Map<String, Object> first = new HashMap<>();
    first.put("id", UUID.randomUUID().toString());
    first.put("name", "Baijini Point TDD");
    first.put("starSystemName", "Stanton");
    Map<String, Object> second = new HashMap<>();
    second.put("id", UUID.randomUUID().toString());
    second.put("name", "Everus Harbor TDD");
    second.put("starSystemName", "Stanton");

    stubEmptyPage(client, "/api/v1/cities?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/space-stations?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/outposts?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/pois?size=10000&sort=name,asc");
    String terminalsBase = "/api/v1/terminals?size=10000&sort=name,asc";
    when(client.get(eq(terminalsBase + "&page=0"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(first), 0, 10000, 5, 2, List.of()));
    when(client.get(eq(terminalsBase + "&page=1"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(second), 1, 10000, 5, 2, List.of()));

    ConcurrentModel model = new ConcurrentModel();
    controller.listData(model);

    assertEquals(
        5L,
        model.getAttribute("totalTerminals"),
        "the chip total must be the backend total, not the fetched list size");
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
    @SuppressWarnings("unchecked")
    List<AdminUexPageController.StarSystemGroup> systems =
        (List<AdminUexPageController.StarSystemGroup>) model.getAttribute("starSystems");
    assertEquals(1, systems.size());
    assertEquals(
        2,
        systems.get(0).terminalCount(),
        "the terminal from the second backend page must not be dropped");
  }

  @Test
  void listData_latestSyncIsNull_whenNoTerminalHasBeenSyncedYet() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminUexPageController controller = new AdminUexPageController(client);
    Map<String, Object> row = new HashMap<>();
    row.put("id", UUID.randomUUID().toString());
    row.put("name", "Unsynced");
    row.put("uexSyncedAt", null);

    stubEmptyPage(client, "/api/v1/cities?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/space-stations?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/outposts?size=10000&sort=name,asc");
    stubEmptyPage(client, "/api/v1/pois?size=10000&sort=name,asc");
    stubPage(client, "/api/v1/terminals?size=10000&sort=name,asc", row);

    ConcurrentModel model = new ConcurrentModel();
    controller.listData(model);

    assertNull(model.getAttribute("latestUexSync"));
  }

  @Test
  void buildHierarchy_matchesTerminalsToCityAndStationByName() {
    AdminUexPageController controller = new AdminUexPageController(mock(BackendApiClient.class));

    CityDto lorville =
        new CityDto(UUID.randomUUID(), "Lorville", "Stanton", "Hurston", true, false);
    SpaceStationDto everus =
        new SpaceStationDto(UUID.randomUUID(), "Everus Harbor", "Stanton", null, true, false);
    TerminalDto termInCity = terminalIn("Lorville TDD", "Stanton", "Lorville", null);
    TerminalDto termInStation = terminalIn("Everus Admin", "Stanton", null, "Everus Harbor");

    List<StarSystemGroup> systems =
        controller.buildHierarchy(
            List.of(lorville),
            List.of(everus),
            List.of(),
            List.of(),
            List.of(termInCity, termInStation));

    assertEquals(1, systems.size());
    StarSystemGroup stanton = systems.get(0);
    assertEquals("Stanton", stanton.name());
    assertEquals(1, stanton.cities().size());
    assertEquals(1, stanton.cities().get(0).terminals().size());
    assertEquals("Lorville TDD", stanton.cities().get(0).terminals().get(0).name());
    assertEquals(1, stanton.spaceStations().size());
    assertEquals(1, stanton.spaceStations().get(0).terminals().size());
    assertEquals("Everus Admin", stanton.spaceStations().get(0).terminals().get(0).name());
    assertTrue(stanton.orphanTerminals().isEmpty());
    assertEquals(2, stanton.locationCount());
    assertEquals(2, stanton.terminalCount());
  }

  @Test
  void buildHierarchy_matchesCaseInsensitively() {
    AdminUexPageController controller = new AdminUexPageController(mock(BackendApiClient.class));

    CityDto lorville =
        new CityDto(UUID.randomUUID(), "Lorville", "Stanton", "Hurston", null, false);
    TerminalDto term = terminalIn("Lorville TDD", "Stanton", "lorville", null);

    List<StarSystemGroup> systems =
        controller.buildHierarchy(
            List.of(lorville), List.of(), List.of(), List.of(), List.of(term));

    assertEquals(1, systems.get(0).cities().get(0).terminals().size());
  }

  @Test
  void buildHierarchy_freeFloatingTerminalsGoToOrphans() {
    AdminUexPageController controller = new AdminUexPageController(mock(BackendApiClient.class));

    TerminalDto orphan = terminalIn("Free Float Trade", "Pyro", null, null);

    List<StarSystemGroup> systems =
        controller.buildHierarchy(List.of(), List.of(), List.of(), List.of(), List.of(orphan));

    assertEquals(1, systems.size());
    StarSystemGroup pyro = systems.get(0);
    assertEquals("Pyro", pyro.name());
    assertEquals(1, pyro.orphanTerminals().size());
    assertEquals(1, pyro.terminalCount());
  }

  @Test
  void buildHierarchy_groupsAcrossMultipleStarSystems() {
    AdminUexPageController controller = new AdminUexPageController(mock(BackendApiClient.class));

    CityDto stantonCity =
        new CityDto(UUID.randomUUID(), "Lorville", "Stanton", "Hurston", null, false);
    CityDto pyroCity =
        new CityDto(UUID.randomUUID(), "Ruin Station Settlement", "Pyro", "Pyro V", null, false);
    OutpostDto pyroOutpost =
        new OutpostDto(UUID.randomUUID(), "Ash R&R", "Pyro", "Pyro III", null, false);

    List<StarSystemGroup> systems =
        controller.buildHierarchy(
            List.of(stantonCity, pyroCity), List.of(), List.of(pyroOutpost), List.of(), List.of());

    assertEquals(2, systems.size());
    assertEquals("Pyro", systems.get(0).name());
    assertEquals(2, systems.get(0).locationCount());
    assertEquals("Stanton", systems.get(1).name());
    assertEquals(1, systems.get(1).locationCount());
  }

  @Test
  void buildHierarchy_terminalWithoutMatchingParentRecordGoesToOrphans() {
    AdminUexPageController controller = new AdminUexPageController(mock(BackendApiClient.class));

    TerminalDto term = terminalIn("Lorville TDD", "Stanton", "Lorville", null);

    List<StarSystemGroup> systems =
        controller.buildHierarchy(List.of(), List.of(), List.of(), List.of(), List.of(term));

    assertEquals(1, systems.size());
    StarSystemGroup stanton = systems.get(0);
    assertTrue(stanton.cities().isEmpty());
    assertEquals(1, stanton.orphanTerminals().size());
    assertEquals("Lorville TDD", stanton.orphanTerminals().get(0).name());
  }

  private static TerminalDto terminalIn(
      String name, String starSystem, String cityName, String stationName) {
    return new TerminalDto(
        UUID.randomUUID(),
        name,
        null,
        starSystem,
        null,
        cityName,
        stationName,
        null,
        null,
        false,
        false,
        null,
        null,
        null,
        false);
  }

  private static void stubEmptyPage(BackendApiClient client, String uri) {
    PageResponse<Map<String, Object>> empty = new PageResponse<>(List.of(), 0, 10, 0, 0, List.of());
    when(client.get(eq(uri + "&page=0"), anyTypeRef())).thenReturn(empty);
  }

  @SafeVarargs
  private static void stubPage(BackendApiClient client, String uri, Map<String, Object>... rows) {
    PageResponse<Map<String, Object>> page =
        new PageResponse<>(List.of(rows), 0, 10, rows.length, 1, List.of());
    when(client.get(eq(uri + "&page=0"), anyTypeRef())).thenReturn(page);
  }
}
