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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.LossReason;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The purchasing queries against real Postgres (REQ-PUR-011, REQ-PUR-013, REQ-PUR-018): the partner
 * history counts, the live tranche sums, the overdue marker that leaves the deal's version alone
 * and the locked read of a deal.
 */
@SpringBootTest
class PurchasingRepositoriesIntegrationTest {

  @Autowired private PurchasingDealRepository dealRepository;
  @Autowired private PurchasingDealTrancheRepository trancheRepository;
  @Autowired private PurchasingDealSettlementRepository settlementRepository;
  @Autowired private PurchasingPartnerRepository partnerRepository;
  @Autowired private PurchasingDelegateRepository delegateRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private User delegate;
  private Material material;
  private PurchasingPartner partner;

  @BeforeEach
  void seed() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    delegate = new User();
    delegate.setId(UUID.randomUUID());
    delegate.setUsername("pur-" + suffix);
    delegate = userRepository.save(delegate);
    material = new Material();
    material.setName("Pur Ore " + suffix);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    material = materialRepository.save(material);
    partner = new PurchasingPartner();
    partner.setKind(PartnerKind.ORG);
    partner.setName("Pur Partner " + suffix);
    partner.setChannel(PartnerChannel.OTHER);
    partner.setContactVia(PartnerContact.OTHER);
    partner.setStatus(PartnerStatus.ACTIVE);
    partner = partnerRepository.save(partner);
  }

  @Test
  void thePartnerHistoryCountsAllDealsAndTheCleanlySettledOnes() {
    PurchasingDeal clean = deal(DealStatus.SETTLED, UUID.randomUUID());
    settlement(clean, 0);
    PurchasingDeal lossy = deal(DealStatus.SETTLED, UUID.randomUUID());
    settlement(lossy, 10);
    deal(DealStatus.FAILED, UUID.randomUUID());

    assertThat(dealRepository.countCleanSettled(partner.getId())).isEqualTo(1);
    assertThat(dealRepository.countByPartners(List.of(partner.getId())))
        .singleElement()
        .satisfies(row -> assertThat(row.count()).isEqualTo(3L));
    assertThat(dealRepository.countCleanSettledByPartners(List.of(partner.getId())))
        .singleElement()
        .satisfies(row -> assertThat(row.count()).isEqualTo(1L));
  }

  @Test
  void liveTranchesSumRequestedAndConfirmedMoneyInTheWindowOnly() {
    PurchasingDeal deal = deal(DealStatus.SUBMITTED, UUID.randomUUID());
    tranche(deal, TrancheState.REQUESTED, 100);
    tranche(deal, TrancheState.CONFIRMED, 200);
    tranche(deal, TrancheState.REFUSED, 400);
    Instant from = Instant.now().minus(Duration.ofHours(1));
    Instant to = Instant.now().plus(Duration.ofHours(1));

    assertThat(trancheRepository.sumLiveByDelegate(delegate.getId(), from, to)).isEqualTo(300);
    assertThat(trancheRepository.sumLive(from, to)).isGreaterThanOrEqualTo(300);
    assertThat(
            trancheRepository.sumLiveByDelegate(
                delegate.getId(), to.plus(Duration.ofHours(1)), to.plus(Duration.ofHours(2))))
        .isZero();
  }

  @Test
  void anOverdueDealIsAnnouncedOnceAndItsVersionStaysPut() {
    PurchasingDeal overdue = deal(DealStatus.DELIVERED, UUID.randomUUID());
    overdue.setSettlementDueAt(Instant.now().minus(Duration.ofDays(2)));
    overdue = dealRepository.saveAndFlush(overdue);
    Long version = overdue.getVersion();
    Instant now = Instant.now();

    assertThat(dealRepository.countOverdue(now)).isGreaterThanOrEqualTo(1);
    assertThat(dealRepository.findOverdueNotYetAnnouncedIds(now, Limit.of(1000)))
        .contains(overdue.getId());

    UUID id = overdue.getId();
    Integer first = transactionTemplate.execute(_ -> dealRepository.markOverdueNotified(id, now));
    Integer second = transactionTemplate.execute(_ -> dealRepository.markOverdueNotified(id, now));
    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(dealRepository.findOverdueNotYetAnnouncedIds(now, Limit.of(1000)))
        .doesNotContain(overdue.getId());
    assertThat(dealRepository.findById(overdue.getId()).orElseThrow().getVersion())
        .isEqualTo(version);
  }

  @Test
  void theDealsOfANeedAreFoundByStatusAndTheDealIsReadUnderALock() {
    UUID need = UUID.randomUUID();
    PurchasingDeal submitted = deal(DealStatus.SUBMITTED, need);
    deal(DealStatus.SETTLED, need);

    assertThat(dealRepository.findByNeedIdAndStatusIn(need, List.of(DealStatus.SUBMITTED)))
        .extracting(PurchasingDeal::getId)
        .containsExactly(submitted.getId());
    PurchasingDeal locked =
        transactionTemplate.execute(
            _ -> dealRepository.findByIdForUpdate(submitted.getId()).orElseThrow());
    assertThat(locked.getId()).isEqualTo(submitted.getId());
  }

  @Test
  void aRevokedAppointmentIsNoLongerActive() {
    PurchasingDelegate appointment = new PurchasingDelegate();
    appointment.setUserId(delegate.getId());
    appointment = delegateRepository.save(appointment);
    Instant now = Instant.now();

    assertThat(delegateRepository.findActiveUserIds(now)).contains(delegate.getId());
    assertThat(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(delegate.getId()))
        .isPresent();

    appointment.setRevokedAt(now);
    delegateRepository.saveAndFlush(appointment);

    assertThat(delegateRepository.findActiveUserIds(Instant.now()))
        .doesNotContain(delegate.getId());
    assertThat(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(delegate.getId())).isEmpty();
  }

  @Test
  void aSettlementIsFoundByItsRemainderRequest() {
    PurchasingDeal deal = deal(DealStatus.DELIVERED, UUID.randomUUID());
    PurchasingDealSettlement settlement = settlement(deal, 0);
    UUID request = UUID.randomUUID();
    settlement.setRemainderRequestId(request);
    settlementRepository.saveAndFlush(settlement);

    assertThat(settlementRepository.findByRemainderRequestId(request))
        .get()
        .extracting(PurchasingDealSettlement::getDealId)
        .isEqualTo(deal.getId());
    assertThat(settlementRepository.findByDealId(deal.getId())).isPresent();
  }

  private PurchasingDeal deal(DealStatus status, UUID need) {
    PurchasingDeal deal = new PurchasingDeal();
    deal.setNeedId(need);
    deal.setRaisingOrgUnitId(Squadron.IRIDIUM_ID);
    deal.setPartnerId(partner.getId());
    deal.setDelegateUserId(delegate.getId());
    deal.setMaterial(material);
    deal.setAmount(5);
    deal.setUnitPrice(100);
    deal.setTotal(500);
    deal.setPaymentModel(PaymentModel.ON_DELIVERY);
    deal.setRiskClass(RiskClassCode.A);
    deal.setStatus(status);
    return dealRepository.saveAndFlush(deal);
  }

  private PurchasingDealSettlement settlement(PurchasingDeal deal, long loss) {
    PurchasingDealSettlement settlement = new PurchasingDealSettlement();
    settlement.setDealId(deal.getId());
    settlement.setMoneyReceived(500);
    settlement.setMoneyPaid(500 - loss);
    settlement.setRemainder(loss);
    settlement.setLossAmount(loss);
    if (loss > 0) {
      settlement.setLossReason(LossReason.FRAUD);
    }
    settlement.setSettledAt(Instant.now());
    return settlementRepository.saveAndFlush(settlement);
  }

  private void tranche(PurchasingDeal deal, TrancheState state, long amount) {
    PurchasingDealTranche tranche = new PurchasingDealTranche();
    tranche.setDealId(deal.getId());
    tranche.setKind(TrancheKind.ON_DELIVERY);
    tranche.setAmount(amount);
    tranche.setState(state);
    trancheRepository.saveAndFlush(tranche);
  }
}
