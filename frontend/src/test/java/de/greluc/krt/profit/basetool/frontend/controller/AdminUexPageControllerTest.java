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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Mockito tests for {@link AdminUexPageController}.
 *
 * <p>Pins the three behaviour buckets that carry real risk in this controller: (1) the override
 * dispatcher fans out to the correct backend URL per kind/action, (2) {@code listData} parses the
 * raw UEX mirror fields and computes the global "latest UEX sync" header attribute, and (3) the
 * hierarchy builder buckets terminals onto the right parent city/station and routes free-floating
 * terminals to the orphans list instead of dropping them.
 */
class AdminUexPageControllerTest {

  // ---------------------------------------------------------------------
  // Override dispatchers
  // ---------------------------------------------------------------------

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
    // The terminals kind now shares the loading-dock dispatcher with the four location kinds —
    // the consolidation eliminated the bespoke terminals path but kept the same backend URL.
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

  // ---------------------------------------------------------------------
  // listData: parse + latestUexSync
  // ---------------------------------------------------------------------

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
    assertEquals(2, model.getAttribute("totalTerminals"));
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

  // ---------------------------------------------------------------------
  // buildHierarchy: parent-matching + orphans
  // ---------------------------------------------------------------------

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
    // UEX has been known to drift casing between the terminal record's
    // cityName ("lorville") and the city record's name ("Lorville"); the
    // match must therefore be case-insensitive or every such terminal would
    // silently end up on the orphan list.
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
    // Terminal with no cityName + no spaceStationName cannot attach to any
    // parent. Without the orphan bucket the admin would have no way to flip
    // its overrides — we'd lose the row entirely on the consolidated page.
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
    // TreeMap with case-insensitive ordering → Pyro < Stanton alphabetically.
    assertEquals("Pyro", systems.get(0).name());
    assertEquals(2, systems.get(0).locationCount());
    assertEquals("Stanton", systems.get(1).name());
    assertEquals(1, systems.get(1).locationCount());
  }

  @Test
  void buildHierarchy_terminalWithoutMatchingParentRecordGoesToOrphans() {
    // Terminal claims to live in "Lorville" but no City record exists yet for
    // that name — realistic when UEX sweeps populated terminals before cities
    // on a fresh install, or when a parent was retired but the terminal still
    // references it. The fallback puts the row on the system's orphans list so
    // the admin can still flip overrides; without it the terminal would simply
    // vanish from the consolidated page.
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

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

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
    when(client.get(eq(uri), anyTypeRef())).thenReturn(empty);
  }

  @SafeVarargs
  private static void stubPage(BackendApiClient client, String uri, Map<String, Object>... rows) {
    PageResponse<Map<String, Object>> page =
        new PageResponse<>(List.of(rows), 0, 10, rows.length, 1, List.of());
    when(client.get(eq(uri), anyTypeRef())).thenReturn(page);
  }
}
