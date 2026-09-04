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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.SyncReportPurgeResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Mockito tests for the "delete reports older than X days" action on {@link
 * AdminSyncReportsPageController}. Pins the three behaviours that carry risk: (1) a blank source
 * purges the combined view and redirects to the combined tab, (2) a source tab is relayed to the
 * backend and the user lands back on that tab, and (3) invalid input / backend failure
 * short-circuit to an error flash without (respectively, regardless of) a backend call.
 */
class AdminSyncReportsPageControllerTest {

  @Test
  void deleteOld_blankSource_purgesCombinedViewAndRedirectsToCombinedTab() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(
            eq("/api/v1/sync-reports?olderThanDays=30"), eq(SyncReportPurgeResultDto.class)))
        .thenReturn(new SyncReportPurgeResultDto(7));
    AdminSyncReportsPageController controller = new AdminSyncReportsPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();

    String view = controller.deleteOld("", 30, attrs);

    assertEquals("redirect:/admin/sync-reports", view);
    assertEquals(7, attrs.getFlashAttributes().get("deletedCount"));
    verify(client).delete("/api/v1/sync-reports?olderThanDays=30", SyncReportPurgeResultDto.class);
  }

  @Test
  void deleteOld_withSource_relaysSourceAndRedirectsToThatTab() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(
            eq("/api/v1/sync-reports?olderThanDays=14&source=UEX"),
            eq(SyncReportPurgeResultDto.class)))
        .thenReturn(new SyncReportPurgeResultDto(2));
    AdminSyncReportsPageController controller = new AdminSyncReportsPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();

    String view = controller.deleteOld("UEX", 14, attrs);

    assertEquals("redirect:/admin/sync-reports/uex", view);
    assertEquals(2, attrs.getFlashAttributes().get("deletedCount"));
    verify(client)
        .delete("/api/v1/sync-reports?olderThanDays=14&source=UEX", SyncReportPurgeResultDto.class);
  }

  @Test
  void deleteOld_rejectsNonPositiveDaysWithoutBackendCall() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminSyncReportsPageController controller = new AdminSyncReportsPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();

    String view = controller.deleteOld("SCWIKI", 0, attrs);

    assertEquals("redirect:/admin/sync-reports/scwiki", view);
    assertEquals("error.admin.syncReports.delete", attrs.getFlashAttributes().get("error"));
    verify(client, never())
        .delete(ArgumentMatchers.<String>any(), ArgumentMatchers.<Class<?>>any());
  }

  @Test
  void deleteOld_canonicalisesTheSourceTabBeforeRelayingIt() {
    // The redirect always upper-cased and trimmed the tab while the relayed backend query took the
    // raw string, so "  scwiki  " landed on the SC Wiki tab but sent an unrecognised source — which
    // the backend reads as "no filter" and purges BOTH catalogues. One canonical value now feeds
    // both.
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(any(String.class), eq(SyncReportPurgeResultDto.class)))
        .thenReturn(new SyncReportPurgeResultDto(3));
    AdminSyncReportsPageController controller = new AdminSyncReportsPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();

    String view = controller.deleteOld("  scwiki  ", 30, attrs);

    assertEquals("redirect:/admin/sync-reports/scwiki", view);
    verify(client)
        .delete(
            "/api/v1/sync-reports?olderThanDays=30&source=SCWIKI", SyncReportPurgeResultDto.class);
  }

  @Test
  void deleteOld_unknownSourceNeverReachesTheRelayedUri() {
    // A crafted tab must not be able to append a second query parameter to the purge request. It is
    // not in the allowlist, so it collapses to the combined purge and the URI carries no `source`.
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(any(String.class), eq(SyncReportPurgeResultDto.class)))
        .thenReturn(new SyncReportPurgeResultDto(0));
    AdminSyncReportsPageController controller = new AdminSyncReportsPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();

    String view = controller.deleteOld("UEX&olderThanDays=99999", 30, attrs);

    assertEquals("redirect:/admin/sync-reports", view);
    verify(client).delete("/api/v1/sync-reports?olderThanDays=30", SyncReportPurgeResultDto.class);
  }

  @Test
  void deleteOldAjax_canonicalisesTheSourceTabBeforeRelayingIt() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(any(String.class), eq(SyncReportPurgeResultDto.class)))
        .thenReturn(new SyncReportPurgeResultDto(5));
    AdminSyncReportsPageController controller = new AdminSyncReportsPageController(client);

    controller.deleteOldAjax("uex", 7);

    verify(client)
        .delete("/api/v1/sync-reports?olderThanDays=7&source=UEX", SyncReportPurgeResultDto.class);
  }

  @Test
  void deleteOld_backendFailureSurfacesErrorFlash() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.delete(any(String.class), eq(SyncReportPurgeResultDto.class)))
        .thenThrow(new BackendServiceException("boom", new RuntimeException(), 500));
    AdminSyncReportsPageController controller = new AdminSyncReportsPageController(client);
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();

    String view = controller.deleteOld("", 30, attrs);

    assertEquals("redirect:/admin/sync-reports", view);
    assertEquals("error.admin.syncReports.delete", attrs.getFlashAttributes().get("error"));
  }
}
