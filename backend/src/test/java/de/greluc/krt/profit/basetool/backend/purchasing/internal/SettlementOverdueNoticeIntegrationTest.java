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

import de.greluc.krt.profit.basetool.backend.admin.api.SystemSettings;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.internal.RuleEvaluationService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrganisationsleitungRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerChannel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerContact;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PartnerStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The overdue-settlement notice end to end against real Postgres (REQ-PUR-003, REQ-PUR-018): the
 * producer raises one event for an overdue deal, and the seeded rule resolves the delegate and the
 * Bereichsleiter and Bereichskoordinator of a governing Bereich — never a Bereichsoperator.
 */
@SpringBootTest
@RecordApplicationEvents
class SettlementOverdueNoticeIntegrationTest {

  @Autowired private SettlementOverdueNoticeProducer producer;
  @Autowired private RuleEvaluationService ruleEvaluationService;
  @Autowired private PurchasingSettingRepository settingRepository;
  @Autowired private PurchasingDealRepository dealRepository;
  @Autowired private PurchasingPartnerRepository partnerRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private OrganisationsleitungRepository organisationsleitungRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private SystemSettings systemSettings;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ApplicationEvents applicationEvents;

  private String switchBefore;
  private Set<UUID> areasBefore;

  @BeforeEach
  void rememberTheSwitchAndTheAreas() {
    switchBefore = systemSettings.getSettingValue("purchasing.enabled").orElse("false");
    areasBefore =
        Set.copyOf(
            settingRepository
                .findById(PurchasingSetting.SINGLETON_ID)
                .orElseThrow()
                .getGoverningAreaIds());
  }

  @AfterEach
  void restoreTheSwitchAndTheAreas() {
    systemSettings.putSettingValue("purchasing.enabled", switchBefore);
    transactionTemplate.executeWithoutResult(
        _ -> {
          PurchasingSetting setting =
              settingRepository.findById(PurchasingSetting.SINGLETON_ID).orElseThrow();
          setting.getGoverningAreaIds().clear();
          setting.getGoverningAreaIds().addAll(areasBefore);
          settingRepository.save(setting);
        });
  }

  @Test
  void theNoticeReachesTheDelegateTheLeaderAndTheCoordinatorButNotTheOperator() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID delegate = user("pur-ov-delegate-" + suffix);
    UUID leader = user("pur-ov-leader-" + suffix);
    UUID coordinator = user("pur-ov-coord-" + suffix);
    UUID operator = user("pur-ov-oper-" + suffix);

    Organisationsleitung ol =
        organisationsleitungRepository.findAll().stream()
            .findFirst()
            .orElseGet(
                () -> {
                  Organisationsleitung created = new Organisationsleitung();
                  created.setName("OL overdue probe " + suffix);
                  created.setShorthand("OV" + suffix.substring(0, 4));
                  return organisationsleitungRepository.save(created);
                });
    Bereich area = new Bereich();
    area.setName("Overdue Bereich " + suffix);
    area.setShorthand("OB" + suffix.substring(0, 4));
    area.setParent(ol);
    area = orgUnitRepository.saveAndFlush(area);
    member(leader, area, MembershipRole.BEREICHSLEITER);
    member(coordinator, area, MembershipRole.BEREICHSKOORDINATOR);
    member(operator, area, MembershipRole.BEREICHSOPERATOR);

    UUID areaId = area.getId();
    transactionTemplate.executeWithoutResult(
        _ -> {
          PurchasingSetting setting =
              settingRepository.findById(PurchasingSetting.SINGLETON_ID).orElseThrow();
          setting.getGoverningAreaIds().clear();
          setting.getGoverningAreaIds().add(areaId);
          settingRepository.save(setting);
        });
    systemSettings.putSettingValue("purchasing.enabled", "true");

    PurchasingDeal deal = overdueDeal(delegate, suffix);

    Integer raised = transactionTemplate.execute(_ -> producer.produce(Instant.now(), 1000));

    assertThat(raised).isGreaterThanOrEqualTo(1);
    NoticeEvent notice =
        applicationEvents.stream(NoticeEvent.class)
            .filter(e -> e.eventType() == NotificationEventType.PURCHASING_SETTLEMENT_OVERDUE)
            .filter(e -> e.entityId().equals(deal.getId()))
            .findFirst()
            .orElseThrow();
    Map<NotificationType, Set<UUID>> recipients = ruleEvaluationService.resolveRecipients(notice);
    assertThat(recipients.get(NotificationType.PURCHASING_SETTLEMENT_OVERDUE))
        .contains(delegate, leader, coordinator)
        .doesNotContain(operator);
  }

  private UUID user(String name) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(name);
    return userRepository.saveAndFlush(user).getId();
  }

  private void member(UUID userId, Bereich area, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, area.getId()));
    membership.setUser(userRepository.findById(userId).orElseThrow());
    membership.setKind(OrgUnitKind.BEREICH);
    membership.setRole(role);
    membership.setJoinedAt(Instant.now());
    membershipRepository.saveAndFlush(membership);
  }

  private PurchasingDeal overdueDeal(UUID delegate, String suffix) {
    Material material = new Material();
    material.setName("Overdue Ore " + suffix);
    material.setType(MaterialType.RAW);
    material.setQuantityType(QuantityType.SCU);
    material = materialRepository.save(material);
    PurchasingPartner partner = new PurchasingPartner();
    partner.setKind(PartnerKind.ORG);
    partner.setName("Overdue Partner " + suffix);
    partner.setChannel(PartnerChannel.OTHER);
    partner.setContactVia(PartnerContact.OTHER);
    partner.setStatus(PartnerStatus.ACTIVE);
    partner = partnerRepository.save(partner);
    PurchasingDeal deal = new PurchasingDeal();
    deal.setNeedId(UUID.randomUUID());
    deal.setRaisingOrgUnitId(Squadron.IRIDIUM_ID);
    deal.setPartnerId(partner.getId());
    deal.setDelegateUserId(delegate);
    deal.setMaterial(material);
    deal.setAmount(5);
    deal.setUnitPrice(100);
    deal.setTotal(500);
    deal.setPaymentModel(PaymentModel.ON_DELIVERY);
    deal.setRiskClass(RiskClassCode.A);
    deal.setStatus(DealStatus.DELIVERED);
    deal.setSettlementDueAt(Instant.now().minus(Duration.ofDays(2)));
    return dealRepository.saveAndFlush(deal);
  }
}
