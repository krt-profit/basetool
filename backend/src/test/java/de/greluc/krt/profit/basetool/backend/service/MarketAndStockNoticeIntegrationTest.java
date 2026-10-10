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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.inventory.api.events.InventoryNotices;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.TransferredLot;
import de.greluc.krt.profit.basetool.backend.materialexchange.api.events.MarketNotices;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Materialbörse and Lager notices end to end against Postgres (REQ-MARKET-021, -022,
 * REQ-INV-056): the seeded rules reach the members who showed interest or own the stock and never
 * the actor, and an offer or request that is gone clears the owner's earlier notices. Runs in a
 * rolled-back transaction.
 */
@SpringBootTest
@Transactional
class MarketAndStockNoticeIntegrationTest {

  private static final UUID OWNER = UUID.fromString("44444444-4444-4444-4444-4444444490c1");
  private static final UUID FAN = UUID.fromString("44444444-4444-4444-4444-4444444490c2");
  private static final UUID OTHER_FAN = UUID.fromString("44444444-4444-4444-4444-4444444490c3");
  private static final UUID ACTOR = UUID.fromString("44444444-4444-4444-4444-4444444490c4");

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;

  private UUID entityId;

  @BeforeEach
  void seed() {
    entityId = UUID.randomUUID();
    user(OWNER, "market-owner");
    user(FAN, "market-fan");
    user(OTHER_FAN, "market-other-fan");
    user(ACTOR, "market-actor");
  }

  private void user(UUID id, String name) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    userRepository.saveAndFlush(user);
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  private void interestNoticeOf(UUID recipient, String entityType) {
    notificationRepository.save(
        Notification.builder()
            .recipientUserId(recipient)
            .type(
                entityType.endsWith("OFFER")
                    ? NotificationType.MATERIAL_EXCHANGE_INTEREST_REGISTERED
                    : NotificationType.MATERIAL_REQUEST_FULFILLMENT_SIGNALLED)
            .entityType(entityType)
            .entityId(entityId)
            .build());
  }

  @Test
  void aWithdrawnOfferTellsEveryInterestedMemberAndClearsTheOwnersInterestNotice() {
    interestNoticeOf(OWNER, "MATERIAL_EXCHANGE_OFFER");

    creationService.createFromEvent(
        MarketNotices.offerUnavailable(
            entityId, "Laranite", "WITHDRAWN", Set.of(FAN, OTHER_FAN), OWNER));

    assertThat(inbox(FAN)).containsExactly(NotificationType.MATERIAL_EXCHANGE_OFFER_UNAVAILABLE);
    assertThat(inbox(OTHER_FAN))
        .containsExactly(NotificationType.MATERIAL_EXCHANGE_OFFER_UNAVAILABLE);
    assertThat(inbox(OWNER)).isEmpty();
  }

  @Test
  void anOfferNeverReachesTheMemberWhoseActionEndedIt() {
    creationService.createFromEvent(
        MarketNotices.offerUnavailable(
            entityId, "Laranite", "STOCK_GONE", Set.of(FAN, ACTOR), ACTOR));

    assertThat(inbox(FAN)).containsExactly(NotificationType.MATERIAL_EXCHANGE_OFFER_UNAVAILABLE);
    assertThat(inbox(ACTOR)).isEmpty();
  }

  @Test
  void anOfferWithNobodyInterestedTellsNobodyButStillClearsTheOwnersNotice() {
    interestNoticeOf(OWNER, "MATERIAL_EXCHANGE_OFFER");

    creationService.createFromEvent(
        MarketNotices.offerUnavailable(entityId, "Laranite", "WITHDRAWN", Set.of(), OWNER));

    assertThat(inbox(OWNER)).isEmpty();
    assertThat(inbox(FAN)).isEmpty();
  }

  @Test
  void aWithdrawnRequestTellsTheSupplyingMembersAndClearsTheOwnersSignalNotice() {
    interestNoticeOf(OWNER, "MATERIAL_EXCHANGE_REQUEST");

    creationService.createFromEvent(
        MarketNotices.requestUnavailable(entityId, "Agricium", Set.of(FAN), OWNER));

    assertThat(inbox(FAN)).containsExactly(NotificationType.MATERIAL_REQUEST_UNAVAILABLE);
    assertThat(inbox(OWNER)).isEmpty();
  }

  @Test
  void stockBookedOutBySomebodyElseTellsTheOwnerButNotTheActor() {
    creationService.createFromEvent(
        InventoryNotices.bookedOutByOther(
            OWNER,
            entityId,
            new ActorRef(ACTOR, "Ada"),
            "DISCARDED",
            List.of(new TransferredLot("Quantanium", 4.0, false, 500, "ARC-L1"))));

    assertThat(inbox(OWNER)).containsExactly(NotificationType.INVENTORY_BOOKED_OUT_BY_OTHER);
    assertThat(inbox(ACTOR)).isEmpty();
  }
}
