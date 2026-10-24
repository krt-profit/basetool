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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

/**
 * The purchasing report as figures, CSV and forum text, its period rules and its access gate
 * (REQ-PUR-023, REQ-PUR-025).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingReportServiceTest {

  private static final YearMonth FIRST = YearMonth.of(2026, 1);
  private static final YearMonth LAST = YearMonth.of(2026, 3);
  private static final UUID NEED_ONE = UUID.randomUUID();
  private static final UUID NEED_TWO = UUID.randomUUID();
  private static final UUID PARTNER = UUID.randomUUID();

  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingDealTrancheRepository trancheRepository;
  @Mock private PurchasingDealDeliveryRepository deliveryRepository;
  @Mock private PurchasingDealSettlementRepository settlementRepository;
  @Mock private PurchasingDealService dealService;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private SupplyNeeds supplyNeeds;
  @Mock private OrgUnitRepository orgUnitRepository;

  private StaticMessageSource messageSource;
  private PurchasingReportService service;
  private Material quantanium;
  private GameItem medpen;

  @BeforeEach
  void setUp() {
    messageSource = new StaticMessageSource();
    messageSource.addMessage("purchasing.report.csv.header", Locale.GERMAN, "Deal;Monat;Eintrag");
    messageSource.addMessage("purchasing.report.csv.yes", Locale.GERMAN, "ja");
    messageSource.addMessage("purchasing.report.csv.no", Locale.GERMAN, "nein");
    messageSource.addMessage(
        "purchasing.report.forum.title", Locale.GERMAN, "Ankauf-Bericht {0} bis {1}");
    messageSource.addMessage(
        "purchasing.report.forum.deals",
        Locale.GERMAN,
        "Geschaefte: {0}, abgerechnet: {1}, gescheitert: {2}, ueberfaellig: {3}");
    messageSource.addMessage(
        "purchasing.report.forum.money",
        Locale.GERMAN,
        "Eingesetzt: {0} aUEC, zurueck: {1} aUEC, netto: {2} aUEC, Verlust: {3} aUEC");
    messageSource.addMessage("purchasing.report.forum.materials", Locale.GERMAN, "Nach Material:");
    messageSource.addMessage(
        "purchasing.report.forum.material",
        Locale.GERMAN,
        "- {0}: {1} Geschaefte, {2} angenommen, {3} aUEC");
    service =
        new PurchasingReportService(
            dealRepository,
            trancheRepository,
            deliveryRepository,
            settlementRepository,
            dealService,
            accessPolicy,
            settingsService,
            supplyNeeds,
            orgUnitRepository,
            messageSource);
    quantanium = new Material();
    quantanium.setId(UUID.randomUUID());
    quantanium.setName("Quantanium");
    quantanium.setPriceBuy(88.0);
    medpen = new GameItem();
    medpen.setId(UUID.randomUUID());
    medpen.setName("Medpen");
  }

  @Test
  void theTotalsSumTheMoneyAndTheDealsOfThePeriod() {
    stubFixture();

    ReportTotalsDto totals = service.report(FIRST, LAST).totals();

    assertThat(totals.dealCount()).isEqualTo(3);
    assertThat(totals.settledCount()).isEqualTo(1);
    assertThat(totals.failedCount()).isEqualTo(1);
    assertThat(totals.overdueCount()).isEqualTo(1);
    assertThat(totals.boughtAmount()).isEqualTo(14.0);
    assertThat(totals.funded()).isEqualTo(1800L);
    assertThat(totals.returned()).isEqualTo(200L);
    assertThat(totals.spent()).isEqualTo(1600L);
    assertThat(totals.loss()).isEqualTo(500L);
  }

  @Test
  void everyMonthOfThePeriodGetsARowEvenWithoutDeals() {
    stubFixture();

    List<ReportMonthDto> months = service.report(FIRST, LAST).months();

    assertThat(months)
        .extracting(ReportMonthDto::month)
        .containsExactly("2026-01", "2026-02", "2026-03");
    ReportMonthDto january = months.get(0);
    assertThat(january.dealCount()).isEqualTo(1);
    assertThat(january.boughtAmount()).isEqualTo(10.0);
    assertThat(january.funded()).isEqualTo(1000L);
    assertThat(january.returned()).isEqualTo(200L);
    assertThat(january.spent()).isEqualTo(800L);
    assertThat(january.loss()).isZero();
    ReportMonthDto february = months.get(1);
    assertThat(february.dealCount()).isEqualTo(2);
    assertThat(february.boughtAmount()).isEqualTo(4.0);
    assertThat(february.funded()).isEqualTo(800L);
    assertThat(february.returned()).isZero();
    assertThat(february.spent()).isEqualTo(800L);
    assertThat(february.loss()).isEqualTo(500L);
    ReportMonthDto march = months.get(2);
    assertThat(march.dealCount()).isZero();
    assertThat(march.funded()).isZero();
  }

  @Test
  void theMaterialRowsWeightThePriceByAmountAndSortByBoughtAmount() {
    stubFixture();

    List<ReportMaterialDto> materials = service.report(FIRST, LAST).materials();

    assertThat(materials)
        .extracting(ReportMaterialDto::name)
        .containsExactly("Quantanium", "Medpen");
    ReportMaterialDto first = materials.get(0);
    assertThat(first.catalogueId()).isEqualTo(quantanium.getId());
    assertThat(first.dealCount()).isEqualTo(2);
    assertThat(first.boughtAmount()).isEqualTo(10.0);
    assertThat(first.averageUnitPrice()).isCloseTo(2000.0 / 15.0, within(1e-9));
    assertThat(first.uexReferencePrice()).isEqualTo(88.0);
    ReportMaterialDto second = materials.get(1);
    assertThat(second.dealCount()).isEqualTo(1);
    assertThat(second.boughtAmount()).isEqualTo(4.0);
    assertThat(second.averageUnitPrice()).isEqualTo(50.0);
    assertThat(second.uexReferencePrice()).isNull();
  }

  @Test
  void theNeedRowsNameTheUnitAndTheEntryAndSortByBoughtAmount() {
    stubFixture();

    List<ReportNeedDto> needs = service.report(FIRST, LAST).needs();

    assertThat(needs).extracting(ReportNeedDto::needId).containsExactly(NEED_ONE, NEED_TWO);
    assertThat(needs.get(0).unit()).isEqualTo("Alpha");
    assertThat(needs.get(0).entry()).isEqualTo("Quantanium");
    assertThat(needs.get(0).dealCount()).isEqualTo(2);
    assertThat(needs.get(0).boughtAmount()).isEqualTo(10.0);
    assertThat(needs.get(1).unit()).isEqualTo("Aegis");
    assertThat(needs.get(1).boughtAmount()).isEqualTo(4.0);
  }

  @Test
  void theCsvHasTheHeaderAndOneLinePerDealWithoutAnyName() {
    stubFixture();

    String csv = service.csv(FIRST, LAST);

    assertThat(csv.split("\n"))
        .containsExactly(
            "Deal;Monat;Eintrag",
            "1;2026-01;Quantanium;10;10;100;1000;SETTLED;1000;200;0;nein",
            "2;2026-02;Quantanium;5;0;200;1000;FAILED;600;0;500;ja",
            "3;2026-02;Medpen;4;4;50;200;DELIVERED;200;0;0;nein");
    assertThat(csv).doesNotContain(PARTNER.toString());
  }

  @Test
  void theForumTextSummarisesThePeriodLineByLine() {
    stubFixture();

    String text = service.forumText(FIRST, LAST);

    assertThat(text)
        .isEqualTo(
            "Ankauf-Bericht 2026-01 bis 2026-03\n"
                + "\n"
                + "Geschaefte: 3, abgerechnet: 1, gescheitert: 1, ueberfaellig: 1\n"
                + "Eingesetzt: 1800 aUEC, zurueck: 200 aUEC, netto: 1600 aUEC, Verlust: 500 aUEC\n"
                + "\n"
                + "Nach Material:\n"
                + "- Quantanium: 2 Geschaefte, 10 angenommen, 133 aUEC\n"
                + "- Medpen: 1 Geschaefte, 4 angenommen, 50 aUEC\n");
  }

  @Test
  void theForumTextOmitsTheMaterialSectionWithoutDeals() {
    stubEmptyPeriod();

    String text = service.forumText(FIRST, LAST);

    assertThat(text)
        .startsWith("Ankauf-Bericht 2026-01 bis 2026-03")
        .doesNotContain("Nach Material");
  }

  @Test
  void theDefaultPeriodIsTheLastTwelveMonthsUpToNow() {
    stubEmptyPeriod();

    PurchasingReportDto report = service.report(null, null);

    YearMonth now = YearMonth.now(ZoneOffset.UTC);
    assertThat(report.to()).isEqualTo(now.toString());
    assertThat(report.from()).isEqualTo(now.minusMonths(11).toString());
    assertThat(report.months()).hasSize(12);
    assertThat(report.totals().dealCount()).isZero();
  }

  @Test
  void aMissingStartCountsBackFromTheGivenEnd() {
    stubEmptyPeriod();

    PurchasingReportDto report = service.report(null, LAST);

    assertThat(report.from()).isEqualTo("2025-04");
    assertThat(report.to()).isEqualTo("2026-03");
  }

  @Test
  void aPeriodOfThirtySixMonthsIsAllowedAndOneMoreIsRefused() {
    stubEmptyPeriod();

    PurchasingReportDto report = service.report(YearMonth.of(2023, 11), YearMonth.of(2026, 10));

    assertThat(report.months()).hasSize(36);
    assertThatThrownBy(() -> service.report(YearMonth.of(2023, 10), YearMonth.of(2026, 10)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aPeriodThatStartsAfterItEndsIsRefused() {
    assertThatThrownBy(() -> service.report(LAST, FIRST)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.csv(LAST, FIRST)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.forumText(LAST, FIRST))
        .isInstanceOf(BadRequestException.class);

    verify(dealRepository, never()).findAll(ArgumentMatchers.<Specification<PurchasingDeal>>any());
  }

  @Test
  void aCallerWithoutTheReportRightIsRefused() {
    when(accessPolicy.canReadReport()).thenReturn(false);

    assertThatThrownBy(() -> service.report(FIRST, LAST)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.csv(FIRST, LAST)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.forumText(FIRST, LAST))
        .isInstanceOf(AccessDeniedException.class);

    verify(dealRepository, never()).findAll(ArgumentMatchers.<Specification<PurchasingDeal>>any());
  }

  private void stubEmptyPeriod() {
    Specification<PurchasingDeal> everything = (_, _, cb) -> cb.conjunction();
    when(accessPolicy.canReadReport()).thenReturn(true);
    when(dealService.visibleToCaller()).thenReturn(everything);
    when(dealRepository.findAll(ArgumentMatchers.<Specification<PurchasingDeal>>any()))
        .thenReturn(List.of());
  }

  private void stubFixture() {
    PurchasingDeal settled =
        deal(
            1L,
            NEED_ONE,
            quantanium,
            null,
            10.0,
            100L,
            1000L,
            DealStatus.SETTLED,
            Instant.parse("2026-01-15T10:00:00Z"));
    PurchasingDeal failed =
        deal(
            2L,
            NEED_ONE,
            quantanium,
            null,
            5.0,
            200L,
            1000L,
            DealStatus.FAILED,
            Instant.parse("2026-02-10T10:00:00Z"));
    failed.setSettlementDueAt(Instant.parse("2026-02-20T10:00:00Z"));
    PurchasingDeal delivered =
        deal(
            3L,
            NEED_TWO,
            null,
            medpen,
            4.0,
            50L,
            200L,
            DealStatus.DELIVERED,
            Instant.parse("2026-02-20T10:00:00Z"));
    delivered.setSettlementDueAt(Instant.now().plus(Duration.ofDays(3)));
    UUID unitOne = UUID.randomUUID();
    UUID unitTwo = UUID.randomUUID();
    Squadron alpha = new Squadron();
    alpha.setId(unitOne);
    alpha.setName("Alpha");
    Squadron aegis = new Squadron();
    aegis.setId(unitTwo);
    aegis.setName("Aegis");
    Specification<PurchasingDeal> everything = (_, _, cb) -> cb.conjunction();
    when(accessPolicy.canReadReport()).thenReturn(true);
    when(dealService.visibleToCaller()).thenReturn(everything);
    when(dealRepository.findAll(ArgumentMatchers.<Specification<PurchasingDeal>>any()))
        .thenReturn(List.of(delivered, settled, failed));
    when(trancheRepository.findByDealIdIn(any()))
        .thenReturn(
            List.of(
                tranche(settled, TrancheState.CONFIRMED, 1000L),
                tranche(failed, TrancheState.CONFIRMED, 600L),
                tranche(failed, TrancheState.REQUESTED, 400L),
                tranche(delivered, TrancheState.CONFIRMED, 200L)));
    when(deliveryRepository.findByDealIdIn(any()))
        .thenReturn(
            List.of(accepted(settled, 10.0), accepted(failed, 0.0), accepted(delivered, 4.0)));
    when(settlementRepository.findByDealIdIn(any()))
        .thenReturn(
            List.of(settlement(settled, 200L, 0L, true), settlement(failed, 0L, 500L, false)));
    when(supplyNeeds.findAll(any()))
        .thenReturn(
            Map.of(
                NEED_ONE, needRef(NEED_ONE, unitOne, "Quantanium"),
                NEED_TWO, needRef(NEED_TWO, unitTwo, "Medpen")));
    when(orgUnitRepository.findAllById(any())).thenReturn(List.<OrgUnit>of(alpha, aegis));
  }

  private static PurchasingDeal deal(
      long no,
      UUID need,
      Material material,
      GameItem gameItem,
      double amount,
      long unitPrice,
      long total,
      DealStatus status,
      Instant createdAt) {
    PurchasingDeal deal = new PurchasingDeal();
    deal.setId(UUID.randomUUID());
    deal.setDealNo(no);
    deal.setNeedId(need);
    deal.setPartnerId(PARTNER);
    deal.setMaterial(material);
    deal.setGameItem(gameItem);
    deal.setAmount(amount);
    deal.setUnitPrice(unitPrice);
    deal.setTotal(total);
    deal.setStatus(status);
    deal.setCreatedAt(createdAt);
    return deal;
  }

  private static PurchasingDealTranche tranche(
      PurchasingDeal deal, TrancheState state, long amount) {
    PurchasingDealTranche tranche = new PurchasingDealTranche();
    tranche.setDealId(deal.getId());
    tranche.setState(state);
    tranche.setAmount(amount);
    return tranche;
  }

  private static PurchasingDealDelivery accepted(PurchasingDeal deal, double amount) {
    PurchasingDealDelivery delivery = new PurchasingDealDelivery();
    delivery.setDealId(deal.getId());
    delivery.setAcceptedAmount(amount);
    return delivery;
  }

  private static PurchasingDealSettlement settlement(
      PurchasingDeal deal, long remainder, long loss, boolean settled) {
    PurchasingDealSettlement settlement = new PurchasingDealSettlement();
    settlement.setDealId(deal.getId());
    settlement.setRemainder(remainder);
    settlement.setLossAmount(loss);
    settlement.setSettledAt(settled ? Instant.parse("2026-03-01T00:00:00Z") : null);
    return settlement;
  }

  private static NeedRef needRef(UUID need, UUID unit, String label) {
    return new NeedRef(
        need, unit, null, null, null, 0, label, false, 10.0, 0.0, 0.0, true, null, NeedStatus.OPEN);
  }
}
