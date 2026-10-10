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

import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeNotices;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The connected-application notices end to end against Postgres (REQ-XCH-040, -041): the seeded
 * rules reach the holders of the client's installations (and of any installation for the global
 * switch), never the admin, and an activation or a switch-on clears what it reverses. Runs in a
 * rolled-back transaction.
 */
@SpringBootTest
@Transactional
class ExchangeNoticeIntegrationTest {

  private static final UUID HOLDER = UUID.fromString("44444444-4444-4444-4444-4444444494c1");
  private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-4444-4444444494c2");
  private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-4444444494c3");

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private UUID clientId;

  @BeforeEach
  void seed() {
    user(HOLDER, "xch-holder");
    user(ADMIN, "xch-admin");
    user(STRANGER, "xch-stranger");
    clientId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO exchange_client (id, client_id, display_name, status, created_at, updated_at,"
            + " version) VALUES (?, ?, 'Trade Helper', 'ACTIVE', now(), now(), 0)",
        clientId,
        "it-client-" + clientId);
    jdbc.update(
        "INSERT INTO exchange_installation (id, exchange_client_id, user_id, key_thumbprint,"
            + " first_seen_at, last_seen_at, created_at, version) VALUES"
            + " (gen_random_uuid(), ?, ?, ?, now(), now(), now(), 0)",
        clientId,
        HOLDER,
        "a".repeat(43));
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

  @Test
  void aSuspensionReachesTheHoldersOfThatClientOnly() {
    creationService.createFromEvent(
        ExchangeNotices.clientSuspended(clientId, "Trade Helper", ADMIN));

    assertThat(inbox(HOLDER)).containsExactly(NotificationType.EXCHANGE_CLIENT_SUSPENDED);
    assertThat(inbox(STRANGER)).isEmpty();
    assertThat(inbox(ADMIN)).isEmpty();
  }

  @Test
  void anActivationReplacesTheSuspensionNotice() {
    creationService.createFromEvent(
        ExchangeNotices.clientSuspended(clientId, "Trade Helper", ADMIN));

    creationService.createFromEvent(
        ExchangeNotices.clientActivated(clientId, "Trade Helper", ADMIN));

    assertThat(inbox(HOLDER)).containsExactly(NotificationType.EXCHANGE_CLIENT_ACTIVATED);
  }

  @Test
  void anUpdateRequirementAndARemovedCapabilityReachTheHolders() {
    creationService.createFromEvent(
        ExchangeNotices.updateRequired(clientId, "Trade Helper", "1.3.0", ADMIN));
    creationService.createFromEvent(
        ExchangeNotices.capabilityRemoved(clientId, "Trade Helper", "exchange.stock.read", ADMIN));

    assertThat(inbox(HOLDER))
        .containsExactlyInAnyOrder(
            NotificationType.EXCHANGE_CLIENT_UPDATE_REQUIRED,
            NotificationType.EXCHANGE_CLIENT_CAPABILITY_REMOVED);
  }

  @Test
  void theGlobalSwitchReachesTheHoldersOfAnyInstallationUntilItIsSwitchedOn() {
    creationService.createFromEvent(ExchangeNotices.switchedOff(ADMIN));
    assertThat(inbox(HOLDER)).containsExactly(NotificationType.EXCHANGE_SWITCHED_OFF);
    assertThat(inbox(STRANGER)).isEmpty();

    creationService.createFromEvent(ExchangeNotices.switchedOn(ADMIN));

    assertThat(inbox(HOLDER)).isEmpty();
  }
}
