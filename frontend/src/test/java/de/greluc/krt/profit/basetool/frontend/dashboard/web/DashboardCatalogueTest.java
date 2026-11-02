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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardColumn;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardOption;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetDescriptor;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.DashboardCatalogueFixture;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link DashboardCatalogue} and {@link DashboardWidgetOptions}: the frontend builds
 * its widget knowledge from the backend's catalogue and falls back to its defaults (REQ-UI-034).
 */
class DashboardCatalogueTest {

  private final DashboardBackendClient client = mock(DashboardBackendClient.class);
  private DashboardCatalogue catalogue;
  private DashboardWidgetOptions options;

  @BeforeEach
  void setUp() {
    DashboardCatalogueFixture.stub(client);
    catalogue = new DashboardCatalogue(client);
    options = new DashboardWidgetOptions(catalogue);
  }

  @Test
  @DisplayName("every frontend widget constant is in the backend's catalogue and the reverse")
  void bothSidesNameTheSameWidgets() {
    assertThat(catalogue.snapshot().widgets())
        .extracting(DashboardWidgetDescriptor::type)
        .containsExactlyInAnyOrder(DashboardWidgetType.values());
  }

  @Test
  @DisplayName("a mode's widgets keep the default order, the wide column first")
  void ofModeSortsWideFirst() {
    List<DashboardWidgetDescriptor> member = catalogue.ofMode(DashboardMode.MEMBER);

    assertThat(member.getFirst().defaultColumn()).isEqualTo(DashboardColumn.WIDE);
    assertThat(member)
        .allSatisfy(widget -> assertThat(widget.mode()).isEqualTo(DashboardMode.MEMBER));
    assertThat(catalogue.ofMode(DashboardMode.ADMIN))
        .extracting(DashboardWidgetDescriptor::type)
        .containsExactly(
            DashboardWidgetType.ADMIN_HUB,
            DashboardWidgetType.DELETION_REQUESTS,
            DashboardWidgetType.DISCORD_REGISTRATIONS,
            DashboardWidgetType.SYNC_STATUS,
            DashboardWidgetType.OPEN_TERMS);
  }

  @Test
  @DisplayName("the options and the status choices are listed in display order")
  void listsOptionsAndStatusesInDisplayOrder() {
    DashboardWidgetDescriptor bankRequests =
        catalogue.descriptor(DashboardWidgetType.BANK_REQUESTS);

    assertThat(bankRequests.optionsAttribute()).isEqualTo("rows status");
    assertThat(bankRequests.statusesAttribute()).isEqualTo("OPEN DECIDED ALL");
    assertThat(catalogue.descriptor(DashboardWidgetType.SYNC_STATUS).optionsAttribute())
        .isEqualTo("range");
    assertThat(
            catalogue.descriptor(DashboardWidgetType.HANGAR_READINESS).offers(DashboardOption.ROWS))
        .isFalse();
  }

  @Test
  @DisplayName("a missing catalogue is a backend failure, not a null")
  void failsWithoutACatalogue() {
    when(client.catalogue()).thenReturn(null);

    assertThatThrownBy(catalogue::snapshot).isInstanceOf(BackendServiceException.class);
  }

  @Test
  @DisplayName("rows fall back to the widget's own default")
  void rowsFallBackToTheDefault() {
    assertThat(options.rows(DashboardWidgetType.LOW_STOCK, null)).isEqualTo(5);
    assertThat(options.rows(DashboardWidgetType.BANK, null)).isEqualTo(3);
    assertThat(options.rows(DashboardWidgetType.BANK_REQUESTS, 4)).isEqualTo(3);
    assertThat(options.rows(DashboardWidgetType.BANK, 10)).isEqualTo(10);
  }

  @Test
  @DisplayName("days read the day choices, the sync range reads the range choices")
  void daysAndRangesReadTheirOwnChoices() {
    assertThat(options.days(DashboardWidgetType.UPCOMING_DAYS, 14)).isEqualTo(14);
    assertThat(options.days(DashboardWidgetType.UPCOMING_DAYS, 30)).isEqualTo(7);
    assertThat(options.days(DashboardWidgetType.SYNC_STATUS, 30)).isEqualTo(30);
    assertThat(options.days(DashboardWidgetType.SYNC_STATUS, 14)).isEqualTo(7);
    assertThat(options.days(DashboardWidgetType.SYNC_STATUS, null)).isEqualTo(7);
  }

  @Test
  @DisplayName("a status the widget does not offer falls back to its default")
  void statusFallsBackToTheDefault() {
    assertThat(options.status(DashboardWidgetType.MARKET, null)).isEqualTo(DashboardStatus.BOTH);
    assertThat(options.status(DashboardWidgetType.MARKET, DashboardStatus.OFFERS))
        .isEqualTo(DashboardStatus.OFFERS);
    assertThat(options.status(DashboardWidgetType.MARKET, DashboardStatus.OPEN))
        .isEqualTo(DashboardStatus.BOTH);
    assertThat(options.status(DashboardWidgetType.NOTIFICATIONS, DashboardStatus.OPEN))
        .isEqualTo(DashboardStatus.UNREAD);
    assertThat(options.status(DashboardWidgetType.BANK_REQUESTS, null))
        .isEqualTo(DashboardStatus.OPEN);
  }

  @Test
  @DisplayName("a widget without the option has no default to fall back to")
  void refusesAnOptionTheWidgetDoesNotOffer() {
    assertThatThrownBy(() -> options.rows(DashboardWidgetType.HANGAR_READINESS, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("the choices of the dialogs come from the catalogue")
  void theDialogChoicesComeFromTheCatalogue() {
    assertThat(catalogue.snapshot().dayChoices()).containsExactly(3, 7, 14);
    assertThat(catalogue.snapshot().rangeChoices()).containsExactly(1, 7, 30);
    assertThat(catalogue.snapshot().rowChoices()).containsExactly(3, 5, 10);
    assertThat(catalogue.snapshot().statusChoices())
        .containsExactlyElementsOf(Arrays.asList(DashboardStatus.values()));
  }
}
