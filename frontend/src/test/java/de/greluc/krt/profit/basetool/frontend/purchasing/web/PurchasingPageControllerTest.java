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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.deal;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.delegate;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.page;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.partner;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DelegateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PauseStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingReportDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingSettingsDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RiskClassDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingPageController.PurchasingTabs;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

/** Unit tests for {@link PurchasingPageController}: the 404 gate, the filters and the model. */
@ExtendWith(MockitoExtension.class)
class PurchasingPageControllerTest {

  private static final PurchasingStateDto NOTHING =
      new PurchasingStateDto(false, false, false, false, false, false, false);

  @Mock private PurchasingBackendClient purchasingClient;
  @Mock private SupplyBackendClient supplyClient;
  @Mock private PurchasingFeatureState featureState;
  @Mock private QualityTierCatalog qualityTierCatalog;

  private PurchasingPageController controller;

  @BeforeEach
  void setUp() {
    controller =
        new PurchasingPageController(
            purchasingClient, supplyClient, featureState, qualityTierCatalog);
  }

  private void switchOn() {
    when(featureState.enabled()).thenReturn(true);
  }

  private static void assertNotFound(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static NeedDto need() {
    return new NeedDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ARGON",
        "ARG",
        null,
        null,
        "SCU",
        null,
        10.0,
        0.0,
        10.0,
        "NORMAL",
        "OPEN",
        true,
        null,
        120L,
        0.0,
        null,
        null,
        true,
        1L,
        Instant.parse("2026-09-01T12:00:00Z"));
  }

  private static SupplyUnitDto unit(String name, boolean mayRaiseNeeds, boolean canManage) {
    return new SupplyUnitDto(
        UUID.randomUUID(), name, name, "SQUADRON", mayRaiseNeeds, true, canManage, 1L);
  }

  @Test
  void everyPageAnswersNotFoundWhileTheSurfaceIsOff() {
    when(featureState.enabled()).thenReturn(false);
    Model model = new ExtendedModelMap();

    assertNotFound(() -> controller.deals(null, false, false, null, null, null, model));
    assertNotFound(() -> controller.deal(UUID.randomUUID(), null, model));
    assertNotFound(() -> controller.partners(null, null, null, null, null, null, model));
    assertNotFound(() -> controller.delegates(null, model));
    assertNotFound(() -> controller.report(null, null, model));

    verifyNoInteractions(purchasingClient, supplyClient, qualityTierCatalog);
  }

  @Test
  void dealsIgnoresAnUnknownStatusAndFallsBackToTheFirstPage() {
    switchOn();
    DealDto row = deal().build();
    when(purchasingClient.deals(null, false, false, 0, 10)).thenReturn(page(List.of(row)));
    Model model = new ExtendedModelMap();

    String view = controller.deals("BOGUS", false, false, -3, null, "results", model);

    assertThat(view).isEqualTo("purchasing/deals :: dealsResults");
    assertThat(model.getAttribute("selectedStatus")).isNull();
    assertThat(model.getAttribute("deals")).isEqualTo(List.of(row));
    assertThat(model.getAttribute("pageSizes")).isEqualTo(List.of(10, 50, 100));
    assertThat(model.getAttribute("purchasingLiveSyncTopic")).isEqualTo("purchasing-deals");
  }

  @ParameterizedTest
  @CsvSource({"10,10", "50,50", "100,100", "25,10", "0,10", "-1,10", "1000,10"})
  void dealsClampsThePageSizeToTheOfferedSizes(int requested, int expected) {
    switchOn();
    when(purchasingClient.deals(null, false, false, 0, expected)).thenReturn(page(List.of()));
    Model model = new ExtendedModelMap();

    controller.deals(null, false, false, null, requested, "results", model);

    assertThat(model.getAttribute("pageSize")).isEqualTo(expected);
  }

  @Test
  void dealsKeepsAKnownStatusAndCarriesEveryFilterIntoThePaginationBase() {
    switchOn();
    when(purchasingClient.deals("FUNDED", true, true, 2, 50)).thenReturn(page(List.of()));
    Model model = new ExtendedModelMap();

    controller.deals("FUNDED", true, true, 2, 50, "RESULTS", model);

    assertThat(model.getAttribute("selectedStatus")).isEqualTo("FUNDED");
    assertThat(model.getAttribute("mine")).isEqualTo(true);
    assertThat(model.getAttribute("overdue")).isEqualTo(true);
    assertThat(model.getAttribute("paginationBaseUrl"))
        .isEqualTo("/purchasing/deals?status=FUNDED&mine=true&overdue=true");
  }

  @Test
  void dealsPaginationBaseOmitsFiltersThatAreOff() {
    switchOn();
    when(purchasingClient.deals(null, true, false, 0, 10)).thenReturn(page(List.of()));
    Model model = new ExtendedModelMap();

    controller.deals(null, true, false, null, null, "results", model);

    assertThat(model.getAttribute("paginationBaseUrl")).isEqualTo("/purchasing/deals?mine=true");
  }

  @Test
  void dealsResultsFragmentSkipsTheNeedPartnerAndUnitLookups() {
    switchOn();
    when(purchasingClient.deals(null, false, false, 0, 10)).thenReturn(page(List.of()));
    Model model = new ExtendedModelMap();

    controller.deals(null, false, false, null, null, "results", model);

    verifyNoInteractions(supplyClient);
    verify(purchasingClient, never()).state();
    verify(purchasingClient, never()).partners(any(), any(), any(), anyInt(), anyInt());
    assertThat(model.containsAttribute("openNeeds")).isFalse();
    assertThat(model.containsAttribute("partners")).isFalse();
    assertThat(model.containsAttribute("pausableUnits")).isFalse();
  }

  @Test
  void aBackendWithoutADealPageYieldsAnEmptyPageOfTheRequestedShape() {
    switchOn();
    Model model = new ExtendedModelMap();

    controller.deals(null, false, false, 4, 50, "results", model);

    assertThat(model.getAttribute("dealsPage"))
        .isEqualTo(new PageResponse<DealDto>(List.of(), 4, 50, 0L, 0, List.of()));
    assertThat(model.getAttribute("deals")).isEqualTo(List.of());
  }

  @Test
  void aDelegateGetsTheOpenNeedsTheActivePartnersAndTheUnitsHeMayPause() {
    switchOn();
    NeedDto need = need();
    PartnerDto active = partner("Orion", "ORG", "ACTIVE", false, null, null);
    SupplyUnitDto pausable = unit("ARGON", true, true);
    when(purchasingClient.deals(null, false, false, 0, 10)).thenReturn(page(List.of()));
    when(purchasingClient.state()).thenReturn(state(true, false, false));
    when(supplyClient.purchasableNeeds()).thenReturn(page(List.of(need)));
    when(purchasingClient.partners(null, null, "ACTIVE", 0, 200)).thenReturn(page(List.of(active)));
    when(supplyClient.units())
        .thenReturn(List.of(pausable, unit("BETA", true, false), unit("GAMMA", false, true)));
    Model model = new ExtendedModelMap();

    String view = controller.deals(null, false, false, null, null, null, model);

    assertThat(view).isEqualTo("purchasing/deals");
    assertThat(model.getAttribute("openNeeds")).isEqualTo(List.of(need));
    assertThat(model.getAttribute("partners")).isEqualTo(List.of(active));
    assertThat(model.getAttribute("pausableUnits")).isEqualTo(List.of(pausable));
  }

  @Test
  void aNonDelegateGetsNoNeedsAndNoPartners() {
    switchOn();
    when(purchasingClient.deals(null, false, false, 0, 10)).thenReturn(page(List.of()));
    when(purchasingClient.state()).thenReturn(state(false, true, false));
    Model model = new ExtendedModelMap();

    controller.deals(null, false, false, null, null, null, model);

    assertThat(model.getAttribute("openNeeds")).isEqualTo(List.of());
    assertThat(model.getAttribute("partners")).isEqualTo(List.of());
    verify(supplyClient, never()).purchasableNeeds();
    verify(purchasingClient, never()).partners(any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void aDelegateWhoseLookupsReturnNothingGetsEmptyLists() {
    switchOn();
    when(purchasingClient.deals(null, false, false, 0, 10)).thenReturn(page(List.of()));
    when(purchasingClient.state()).thenReturn(state(true, false, false));
    when(supplyClient.units()).thenReturn(null);
    Model model = new ExtendedModelMap();

    controller.deals(null, false, false, null, null, null, model);

    assertThat(model.getAttribute("openNeeds")).isEqualTo(List.of());
    assertThat(model.getAttribute("partners")).isEqualTo(List.of());
    assertThat(model.getAttribute("pausableUnits")).isEqualTo(List.of());
  }

  @Test
  void aFailingUnitReadLeavesThePauseUnitsEmpty() {
    switchOn();
    when(purchasingClient.deals(null, false, false, 0, 10)).thenReturn(page(List.of()));
    when(purchasingClient.state()).thenReturn(state(false, true, false));
    when(supplyClient.units()).thenThrow(new IllegalStateException("down"));
    Model model = new ExtendedModelMap();

    controller.deals(null, false, false, null, null, null, model);

    assertThat(model.getAttribute("pausableUnits")).isEqualTo(List.of());
  }

  @Test
  void aFailingPauseReadShowsNothingPaused() {
    switchOn();
    when(purchasingClient.deals(null, false, false, 0, 10)).thenReturn(page(List.of()));
    when(purchasingClient.pauseState()).thenThrow(new IllegalStateException("down"));
    Model model = new ExtendedModelMap();

    controller.deals(null, false, false, null, null, "results", model);

    assertThat(model.getAttribute("pauseState"))
        .isEqualTo(new PauseStateDto(false, null, null, List.of()));
  }

  @Test
  void aFailedStateReadDegradesToAllCapabilitiesOff() {
    when(purchasingClient.state()).thenThrow(new IllegalStateException("down"));

    assertThat(controller.purchasingState()).isEqualTo(NOTHING);
  }

  @Test
  void anEmptyStateBodyDegradesToAllCapabilitiesOff() {
    when(purchasingClient.state()).thenReturn(null);

    assertThat(controller.purchasingState()).isEqualTo(NOTHING);
  }

  @Test
  void aReadStateIsPassedOnUnchanged() {
    PurchasingStateDto state = state(true, true, true);
    when(purchasingClient.state()).thenReturn(state);

    assertThat(controller.purchasingState()).isSameAs(state);
  }

  @Test
  void aDelegateSeesThePartnersAndDelegatesTabs() {
    assertThat(controller.purchasingTabs(state(true, false, false)))
        .isEqualTo(new PurchasingTabs(true, false, false));
  }

  @Test
  void theGoverningLeadershipSeesThePartnersAndDelegatesTabs() {
    assertThat(controller.purchasingTabs(state(false, true, false)))
        .isEqualTo(new PurchasingTabs(true, true, false));
  }

  @Test
  void theReportTabFollowsTheReportCapabilityAlone() {
    PurchasingStateDto reader =
        new PurchasingStateDto(true, false, false, true, false, true, false);

    assertThat(controller.purchasingTabs(reader)).isEqualTo(new PurchasingTabs(false, false, true));
    assertThat(controller.purchasingTabs(NOTHING))
        .isEqualTo(new PurchasingTabs(false, false, false));
  }

  @Test
  void dealAnswersNotFoundWhenTheBackendSendsNoBody() {
    switchOn();
    UUID id = UUID.randomUUID();
    when(purchasingClient.deal(id)).thenReturn(null);

    assertNotFound(() -> controller.deal(id, null, new ExtendedModelMap()));
  }

  @Test
  void dealDetailFragmentSkipsTheLocationAndPartnerLookups() {
    switchOn();
    DealDto deal = deal().build();
    when(purchasingClient.deal(deal.id())).thenReturn(deal);
    Model model = new ExtendedModelMap();

    String view = controller.deal(deal.id(), "detail", model);

    assertThat(view).isEqualTo("purchasing/deal :: dealDetail");
    assertThat(model.getAttribute("deal")).isSameAs(deal);
    assertThat(model.containsAttribute("locations")).isFalse();
    assertThat(model.containsAttribute("partners")).isFalse();
    verify(purchasingClient, never()).locations();
    verify(purchasingClient, never()).partners(any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void dealFullPageLoadsLocationsAndThePartnersOfTheDelegateWhoOwnsTheDeal() {
    switchOn();
    DealDto deal = deal().mine(true).build();
    LocationReferenceDto location = new LocationReferenceDto(UUID.randomUUID(), "Lorville");
    PartnerDto active = partner("Orion", "ORG", "ACTIVE", false, null, null);
    when(purchasingClient.deal(deal.id())).thenReturn(deal);
    when(purchasingClient.locations()).thenReturn(List.of(location));
    when(purchasingClient.partners(null, null, "ACTIVE", 0, 200)).thenReturn(page(List.of(active)));
    Model model = new ExtendedModelMap();

    String view = controller.deal(deal.id(), null, model);

    assertThat(view).isEqualTo("purchasing/deal");
    assertThat(model.getAttribute("locations")).isEqualTo(List.of(location));
    assertThat(model.getAttribute("partners")).isEqualTo(List.of(active));
  }

  @Test
  void dealFullPageOffersNoPartnersForSomeoneElsesDeal() {
    switchOn();
    DealDto deal = deal().mine(false).build();
    when(purchasingClient.deal(deal.id())).thenReturn(deal);
    Model model = new ExtendedModelMap();

    controller.deal(deal.id(), null, model);

    assertThat(model.getAttribute("partners")).isEqualTo(List.of());
    assertThat(model.getAttribute("locations")).isEqualTo(List.of());
    verify(purchasingClient, never()).partners(any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void partnersTrimsTheQueryAndKeepsAKnownKindAndStatus() {
    switchOn();
    when(purchasingClient.partners("Orion", "ORG", "BLOCKED", 0, 100)).thenReturn(page(List.of()));
    Model model = new ExtendedModelMap();

    String view = controller.partners("  Orion ", "ORG", "BLOCKED", null, 100, "results", model);

    assertThat(view).isEqualTo("purchasing/partners :: partnersResults");
    assertThat(model.getAttribute("query")).isEqualTo("Orion");
    assertThat(model.getAttribute("selectedKind")).isEqualTo("ORG");
    assertThat(model.getAttribute("selectedStatus")).isEqualTo("BLOCKED");
    assertThat(model.getAttribute("paginationBaseUrl"))
        .isEqualTo("/purchasing/partners?query=Orion&kind=ORG&status=BLOCKED");
  }

  @Test
  void partnersDropsAnUnknownKindAndStatusAndABlankQuery() {
    switchOn();
    when(purchasingClient.partners(null, null, null, 3, 10)).thenReturn(page(List.of()));
    Model model = new ExtendedModelMap();

    String view = controller.partners("   ", "ALIEN", "GONE", 3, 11, null, model);

    assertThat(view).isEqualTo("purchasing/partners");
    assertThat(model.getAttribute("query")).isNull();
    assertThat(model.getAttribute("selectedKind")).isNull();
    assertThat(model.getAttribute("selectedStatus")).isNull();
    assertThat(model.getAttribute("pageSize")).isEqualTo(10);
    assertThat(model.getAttribute("paginationBaseUrl")).isEqualTo("/purchasing/partners");
  }

  @Test
  void partnersEncodesTheQueryInThePaginationBase() {
    switchOn();
    when(purchasingClient.partners("a b", null, null, 0, 10)).thenReturn(page(List.of()));
    Model model = new ExtendedModelMap();

    controller.partners("a b", null, null, null, null, "results", model);

    assertThat(model.getAttribute("paginationBaseUrl"))
        .isEqualTo("/purchasing/partners?query=a%20b");
  }

  @Test
  void partnersWithoutABackendPageYieldsAnEmptyList() {
    switchOn();
    Model model = new ExtendedModelMap();

    controller.partners(null, null, null, 2, null, "results", model);

    assertThat(model.getAttribute("partners")).isEqualTo(List.of());
    assertThat(model.getAttribute("partnersPage"))
        .isEqualTo(new PageResponse<PartnerDto>(List.of(), 2, 10, 0L, 0, List.of()));
  }

  @Test
  void delegatesRendersTheListOrItsFragment() {
    switchOn();
    DelegateDto appointed = delegate("Tester", null, 50_000L, null);
    when(purchasingClient.delegates()).thenReturn(List.of(appointed));
    Model page = new ExtendedModelMap();
    Model fragment = new ExtendedModelMap();

    assertThat(controller.delegates(null, page)).isEqualTo("purchasing/delegates");
    assertThat(controller.delegates("results", fragment))
        .isEqualTo("purchasing/delegates :: delegatesResults");

    assertThat(page.getAttribute("delegates")).isEqualTo(List.of(appointed));
  }

  @Test
  void delegatesWithoutABackendBodyYieldsAnEmptyList() {
    switchOn();
    when(purchasingClient.delegates()).thenReturn(null);
    Model model = new ExtendedModelMap();

    controller.delegates(null, model);

    assertThat(model.getAttribute("delegates")).isEqualTo(List.of());
  }

  @Test
  void reportAcceptsOnlyYearMonthPeriods() {
    switchOn();
    when(purchasingClient.report("2026-09", null)).thenReturn(null);
    Model model = new ExtendedModelMap();

    String view = controller.report("2026-09", "2026-13", model);

    assertThat(view).isEqualTo("purchasing/report");
    assertThat(model.getAttribute("from")).isEqualTo("2026-09");
    assertThat(model.getAttribute("to")).isNull();
    assertThat(model.getAttribute("report")).isNull();
  }

  @Test
  void reportDropsMalformedMonthsOnBothEnds() {
    switchOn();
    when(purchasingClient.report(null, null)).thenReturn(null);

    controller.report("2026-9", "September", new ExtendedModelMap());

    verify(purchasingClient).report(null, null);
  }

  @Test
  void reportEchoesThePeriodTheBackendResolved() {
    switchOn();
    PurchasingReportDto report =
        new PurchasingReportDto("2026-08", "2026-09", null, List.of(), List.of(), List.of());
    when(purchasingClient.report(null, null)).thenReturn(report);
    Model model = new ExtendedModelMap();

    controller.report(null, null, model);

    assertThat(model.getAttribute("report")).isSameAs(report);
    assertThat(model.getAttribute("from")).isEqualTo("2026-08");
    assertThat(model.getAttribute("to")).isEqualTo("2026-09");
  }

  @Test
  void adminSettingsOffersOnlyBereicheAndActiveTiersAndEvictsTheFeatureState() {
    PurchasingSettingsDto settings =
        new PurchasingSettingsDto(
            true, null, null, null, null, null, 0L, null, "MONTH", 14, List.of(), false, null, 1L);
    QualityTierDto active =
        new QualityTierDto(UUID.randomUUID(), "HIGH", 700, "Hoch", "High", 1, true, 1L);
    QualityTierDto retired =
        new QualityTierDto(UUID.randomUUID(), "OLD", 100, "Alt", "Old", 2, false, 1L);
    OrgUnitMembershipOptionDto area =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "Profit", "PRF", "BEREICH", true);
    OrgUnitMembershipOptionDto squadron =
        new OrgUnitMembershipOptionDto(UUID.randomUUID(), "ARGON", "ARG", "SQUADRON", true);
    RiskClassDto riskClass = new RiskClassDto("A", 100_000L, 50, false, false, 1L);
    when(purchasingClient.settings()).thenReturn(settings);
    when(qualityTierCatalog.all()).thenReturn(List.of(active, retired));
    when(purchasingClient.activeOrgUnits()).thenReturn(List.of(area, squadron));
    when(purchasingClient.riskClasses()).thenReturn(List.of(riskClass));
    when(purchasingClient.fundingAccounts()).thenReturn(null);
    Model model = new ExtendedModelMap();

    String view = controller.adminSettings(model);

    assertThat(view).isEqualTo("purchasing/admin/purchasing-settings");
    assertThat(model.getAttribute("settings")).isSameAs(settings);
    assertThat(model.getAttribute("areas")).isEqualTo(List.of(area));
    assertThat(model.getAttribute("tiers")).isEqualTo(List.of(active));
    assertThat(model.getAttribute("riskClasses")).isEqualTo(List.of(riskClass));
    assertThat(model.getAttribute("fundingAccounts")).isEqualTo(List.of());
    verify(featureState).evict();
    verifyNoMoreInteractions(featureState);
  }
}
