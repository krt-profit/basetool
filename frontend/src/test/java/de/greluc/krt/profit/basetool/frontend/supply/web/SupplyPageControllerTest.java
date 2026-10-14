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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStatsDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/** Unit tests for {@link SupplyPageController}: the 404 gate and the statistics view. */
@ExtendWith(MockitoExtension.class)
class SupplyPageControllerTest {

  @Mock private SupplyBackendClient supplyClient;
  @Mock private SupplyFeatureState featureState;
  @Mock private QualityTierCatalog qualityTierCatalog;
  @Mock private FrontendAuthHelperService authHelper;

  private SupplyPageController controller;

  @BeforeEach
  void setUp() {
    controller =
        new SupplyPageController(supplyClient, featureState, qualityTierCatalog, authHelper);
  }

  @Test
  void statsAnswersNotFoundWhileTheSurfaceIsOff() {
    when(featureState.enabled()).thenReturn(false);
    Model model = new ExtendedModelMap();

    assertThatThrownBy(() -> controller.stats(6, null, model))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

    verifyNoInteractions(supplyClient);
  }

  @Test
  void statsRendersTheFullPageByDefault() {
    when(featureState.enabled()).thenReturn(true);
    SupplyStatsDto stats = new SupplyStatsDto(List.of(), List.of(), List.of());
    when(supplyClient.stats(6)).thenReturn(stats);
    Model model = new ExtendedModelMap();

    String view = controller.stats(6, null, model);

    assertThat(view).isEqualTo("supply/stats");
    assertThat(model.getAttribute("stats")).isEqualTo(stats);
    assertThat(model.getAttribute("months")).isEqualTo(6);
  }

  @ParameterizedTest
  @CsvSource({"results", "RESULTS"})
  void statsRendersOnlyTheResultsFragmentOnRequest(String fragment) {
    when(featureState.enabled()).thenReturn(true);
    when(supplyClient.stats(12)).thenReturn(new SupplyStatsDto(List.of(), List.of(), List.of()));
    Model model = new ExtendedModelMap();

    String view = controller.stats(12, fragment, model);

    assertThat(view).isEqualTo("supply/stats :: statsResults");
    assertThat(model.getAttribute("months")).isEqualTo(12);
  }

  @ParameterizedTest
  @CsvSource({"0,1", "-5,1", "36,36", "100,36"})
  void statsClampsTheSelectedMonthsWithoutChangingTheBackendRequest(int requested, int shown) {
    when(featureState.enabled()).thenReturn(true);
    when(supplyClient.stats(requested))
        .thenReturn(new SupplyStatsDto(List.of(), List.of(), List.of()));
    Model model = new ExtendedModelMap();

    controller.stats(requested, null, model);

    assertThat(model.getAttribute("months")).isEqualTo(shown);
  }
}
