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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardColumn;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardView;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetResponse;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link DashboardReader}: what a stored dashboard renders in the active context, the
 * deletion of instances whose role is gone and the catalogue (REQ-UI-034).
 */
class DashboardReaderTest {

  private static final NavigationAccess MEMBER =
      new NavigationAccess(Set.of("KRT_MEMBER"), Set.of());
  private static final NavigationAccess ADMIN =
      new NavigationAccess(Set.of("ADMIN", "KRT_MEMBER"), Set.of());
  private static final NavigationAccess GUEST = new NavigationAccess(Set.of(), Set.of());

  private final DashboardBackendClient client = mock(DashboardBackendClient.class);
  private DashboardReader reader;

  @BeforeEach
  void setUp() {
    reader = new DashboardReader(client);
  }

  private static DashboardWidgetResponse widget(
      DashboardWidgetType type, DashboardColumn column, UUID unit) {
    return new DashboardWidgetResponse(
        UUID.randomUUID(), type, column, 0, unit, null, null, (DashboardStatus) null);
  }

  private static DashboardResponse stored(boolean customised, DashboardWidgetResponse... widgets) {
    return new DashboardResponse(DashboardMode.MEMBER, 4, customised, List.of(widgets));
  }

  private DashboardView read(
      NavigationAccess access, UUID active, List<OrgUnitMembershipOptionDto> units) {
    return reader.read(DashboardMode.MEMBER, access, active, units);
  }

  @Test
  @DisplayName("the instances split into the two columns in stored order")
  void splitsTheColumns() {
    when(client.dashboard(DashboardMode.MEMBER))
        .thenReturn(
            stored(
                true,
                widget(DashboardWidgetType.UPCOMING_DAYS, DashboardColumn.WIDE, null),
                widget(DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW, null),
                widget(DashboardWidgetType.INFORMATION, DashboardColumn.WIDE, null)));

    DashboardView view = read(MEMBER, null, List.of());

    assertThat(view.loaded()).isTrue();
    assertThat(view.version()).isEqualTo(4);
    assertThat(view.wide())
        .extracting(item -> item.widget().type())
        .containsExactly(DashboardWidgetType.UPCOMING_DAYS, DashboardWidgetType.INFORMATION);
    assertThat(view.narrow())
        .extracting(item -> item.widget().type())
        .containsExactly(DashboardWidgetType.FAVOURITES);
  }

  @Test
  @DisplayName("the admin dashboard offers the five admin widgets to an administrator only")
  void offersTheAdminCatalogueToAdministrators() {
    when(client.dashboard(DashboardMode.ADMIN))
        .thenReturn(new DashboardResponse(DashboardMode.ADMIN, 0, false, List.of()));

    DashboardView forAdmin = reader.read(DashboardMode.ADMIN, ADMIN, null, List.of());
    DashboardView forMember = reader.read(DashboardMode.ADMIN, MEMBER, null, List.of());

    assertThat(forAdmin.catalogue())
        .containsExactly(
            DashboardWidgetType.ADMIN_HUB,
            DashboardWidgetType.DELETION_REQUESTS,
            DashboardWidgetType.DISCORD_REGISTRATIONS,
            DashboardWidgetType.SYNC_STATUS,
            DashboardWidgetType.OPEN_TERMS);
    assertThat(forMember.catalogue()).isEmpty();
  }

  @Test
  @DisplayName("an admin widget is deleted once the member lost the admin role")
  void deletesAdminWidgetsOnRoleLoss() {
    DashboardResponse stored =
        new DashboardResponse(
            DashboardMode.ADMIN,
            3,
            true,
            List.of(widget(DashboardWidgetType.OPEN_TERMS, DashboardColumn.NARROW, null)));
    when(client.dashboard(DashboardMode.ADMIN)).thenReturn(stored);
    when(client.pruneDashboard(DashboardMode.ADMIN, List.of(DashboardWidgetType.OPEN_TERMS)))
        .thenReturn(new DashboardResponse(DashboardMode.ADMIN, 4, true, List.of()));

    DashboardView view = reader.read(DashboardMode.ADMIN, MEMBER, null, List.of());

    assertThat(view.empty()).isTrue();
    assertThat(view.version()).isEqualTo(4);
  }

  @Test
  @DisplayName("an instance pinned to a unit other than the pinned context is unavailable")
  void greysAnInstancePinnedToAnotherUnit() {
    UUID alpha = UUID.randomUUID();
    UUID beta = UUID.randomUUID();
    when(client.dashboard(DashboardMode.MEMBER))
        .thenReturn(
            stored(true, widget(DashboardWidgetType.UPCOMING_DAYS, DashboardColumn.WIDE, alpha)));
    List<OrgUnitMembershipOptionDto> units =
        List.of(new OrgUnitMembershipOptionDto(alpha, "Alpha", "ALF", "SQUADRON", true));

    DashboardView inBeta = read(MEMBER, beta, units);
    DashboardView inAlpha = read(MEMBER, alpha, units);
    DashboardView inAll = read(MEMBER, null, units);

    assertThat(inBeta.wide().getFirst().available()).isFalse();
    assertThat(inBeta.wide().getFirst().unitName()).isEqualTo("Alpha");
    assertThat(inAlpha.wide().getFirst().available()).isTrue();
    assertThat(inAll.wide().getFirst().available()).isTrue();
  }

  @Test
  @DisplayName("an instance whose role the caller lost is deleted and not rendered")
  void deletesTheInstancesOfALostRole() {
    DashboardResponse before =
        stored(
            true,
            widget(DashboardWidgetType.UPCOMING_DAYS, DashboardColumn.WIDE, null),
            widget(DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW, null));
    DashboardResponse after =
        new DashboardResponse(
            DashboardMode.MEMBER,
            5,
            true,
            List.of(widget(DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW, null)));
    when(client.dashboard(DashboardMode.MEMBER)).thenReturn(before);
    when(client.pruneDashboard(DashboardMode.MEMBER, List.of(DashboardWidgetType.UPCOMING_DAYS)))
        .thenReturn(after);

    DashboardView view = read(GUEST, null, List.of());

    assertThat(view.wide()).isEmpty();
    assertThat(view.narrow()).hasSize(1);
    assertThat(view.version()).isEqualTo(5);
  }

  @Test
  @DisplayName("the unchanged default is filtered without a write")
  void filtersTheDefaultWithoutAWrite() {
    when(client.dashboard(DashboardMode.MEMBER))
        .thenReturn(
            stored(
                false,
                widget(DashboardWidgetType.UPCOMING_DAYS, DashboardColumn.WIDE, null),
                widget(DashboardWidgetType.FAVOURITES, DashboardColumn.NARROW, null)));

    DashboardView view = read(GUEST, null, List.of());

    assertThat(view.wide()).isEmpty();
    assertThat(view.narrow()).hasSize(1);
    verify(client, never()).pruneDashboard(any(), any());
  }

  @Test
  @DisplayName("the catalogue lists the widgets the caller may add")
  void listsTheCatalogueOfPermittedWidgets() {
    when(client.dashboard(DashboardMode.MEMBER)).thenReturn(stored(false));

    assertThat(read(MEMBER, null, List.of()).catalogue())
        .containsExactlyInAnyOrder(
            DashboardWidgetType.UPCOMING_DAYS,
            DashboardWidgetType.INFORMATION,
            DashboardWidgetType.FAVOURITES,
            DashboardWidgetType.NOTIFICATIONS,
            DashboardWidgetType.NEXT_MISSION,
            DashboardWidgetType.HANGAR_READINESS,
            DashboardWidgetType.ASSIGNED_ORDERS,
            DashboardWidgetType.LOW_STOCK,
            DashboardWidgetType.BANK);
    assertThat(read(GUEST, null, List.of()).catalogue())
        .doesNotContain(DashboardWidgetType.UPCOMING_DAYS)
        .contains(DashboardWidgetType.INFORMATION);
  }

  @Test
  @DisplayName("a backend failure shows nothing and turns editing off")
  void aFailedReadIsNotLoaded() {
    when(client.dashboard(eq(DashboardMode.MEMBER)))
        .thenThrow(new BackendServiceException("down", null, 503));

    DashboardView view = read(MEMBER, null, List.of());

    assertThat(view.loaded()).isFalse();
    assertThat(view.empty()).isTrue();
  }
}
