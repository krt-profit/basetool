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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ConnectedAppDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ConnectedInstallationDto;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sweep of disconnected installations and client revocations past their retention
 * (REQ-XCH-035): the 89/91-day boundaries, the installations a whole-client disconnect ended, live
 * installations and other members left alone, one audit event per run that deleted something, and a
 * second run that deletes nothing.
 */
@SpringBootTest
@Transactional
class ExchangeConnectionRetentionServiceTest {

  private static final Duration RETENTION = Duration.ofDays(90);

  @Autowired private ExchangeConnectionRetentionService retentionService;
  @Autowired private ConnectedAppsService connectedAppsService;
  @Autowired private UserRepository userRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeClientRevocationRepository revocationRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  private Instant now;
  private UUID member;
  private UUID otherMember;
  private ExchangeClient kept;
  private ExchangeClient ended;
  private Map<String, UUID> installations;

  @BeforeEach
  void setUp() {
    now = Instant.now();
    member = user("retention-sweep-member");
    otherMember = user("retention-sweep-other");
    kept = client("sweep-kept-client");
    ended = client("sweep-ended-client");
    installations =
        Map.of(
            "revoked91", installation(kept, member, 'a', days(95), days(91)),
            "revoked89", installation(kept, member, 'b', days(95), days(89)),
            "live", installation(kept, member, 'c', days(1), null),
            "seenBeforeRecentRevocation", installation(kept, member, 'd', days(92), null),
            "endedByOldRevocation", installation(ended, member, 'e', days(95), null),
            "reconnectedAfterOldRevocation", installation(ended, member, 'f', days(10), null),
            "otherMemberSeenLongAgo", installation(ended, otherMember, 'g', days(95), null),
            "otherMemberRevoked89", installation(kept, otherMember, 'h', days(95), days(89)));
    revocationRepository.upsert(ended.getId(), member, days(91));
    revocationRepository.upsert(kept.getId(), member, days(89));
  }

  @Test
  void theSweepDeletesOnlyWhatWasDisconnectedMoreThanTheRetentionAgo() {
    ExchangeConnectionRetentionService.Purged purged =
        retentionService.purgeBefore(now.minus(RETENTION));

    assertThat(purged.installations()).isEqualTo(2);
    assertThat(purged.revocations()).isEqualTo(1);
    assertThat(remainingInstallations())
        .containsExactlyInAnyOrder(
            installations.get("revoked89"),
            installations.get("live"),
            installations.get("seenBeforeRecentRevocation"),
            installations.get("reconnectedAfterOldRevocation"),
            installations.get("otherMemberSeenLongAgo"),
            installations.get("otherMemberRevoked89"));
    assertThat(revocationRepository.findAllByUserId(member))
        .singleElement()
        .satisfies(r -> assertThat(r.clientId()).isEqualTo(kept.getClientId()));
  }

  @Test
  void anInstallationTheOldRevocationEndedDoesNotComeBackAsConnected() {
    retentionService.purgeBefore(now.minus(RETENTION));

    List<ConnectedAppDto> apps = connectedAppsService.list(member);

    assertThat(apps)
        .flatExtracting(ConnectedAppDto::installations)
        .extracting(ConnectedInstallationDto::id)
        .containsExactlyInAnyOrder(
            installations.get("live"), installations.get("reconnectedAfterOldRevocation"));
  }

  @Test
  void aRunThatDeletedRowsRecordsOneAuditEventWithTheCountsOnly() {
    retentionService.purgeBefore(now.minus(RETENTION));
    entityManager.flush();

    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT domain, subject_id, subject_label, target_user_id, details FROM audit_event"
                + " WHERE event_type = 'EXCHANGE_CONNECTIONS_PURGED'");
    assertThat(event.get("domain")).isEqualTo("CONNECTED_APPS");
    assertThat(event.get("subject_id")).isNull();
    assertThat(event.get("subject_label")).isNull();
    assertThat(event.get("target_user_id")).isNull();
    assertThat((String) event.get("details"))
        .startsWith("installations=2 revocations=1 cutoff=")
        .doesNotContain(member.toString())
        .doesNotContain(otherMember.toString());
  }

  @Test
  void aSecondRunDeletesNothingAndRecordsNothing() {
    retentionService.purgeBefore(now.minus(RETENTION));
    List<UUID> afterFirst = remainingInstallations();

    ExchangeConnectionRetentionService.Purged second =
        retentionService.purgeBefore(now.minus(RETENTION));

    entityManager.flush();

    assertThat(second.total()).isZero();
    assertThat(remainingInstallations()).containsExactlyInAnyOrderElementsOf(afterFirst);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'EXCHANGE_CONNECTIONS_PURGED'",
                Integer.class))
        .isEqualTo(1);
  }

  /**
   * Returns the ids of the test's installations still stored.
   *
   * @return the remaining ids
   */
  private @NotNull List<UUID> remainingInstallations() {
    return jdbc.queryForList(
        "SELECT id FROM exchange_installation WHERE exchange_client_id IN (?, ?)",
        UUID.class,
        kept.getId(),
        ended.getId());
  }

  /**
   * Returns the time the given number of days before the test's now.
   *
   * @param days the days back
   * @return the time
   */
  private @NotNull Instant days(int days) {
    return now.minus(Duration.ofDays(days));
  }

  /**
   * Stores an active member.
   *
   * @param username the username
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    return userRepository.saveAndFlush(user).getId();
  }

  /**
   * Stores an active registry client.
   *
   * @param clientId the Keycloak client id
   * @return the client
   */
  private @NotNull ExchangeClient client(@NotNull String clientId) {
    ExchangeClient client = new ExchangeClient();
    client.setClientId(clientId);
    client.setDisplayName(clientId);
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT));
    return clientRepository.saveAndFlush(client);
  }

  /**
   * Stores an installation with the given times.
   *
   * @param client the client
   * @param owner the member
   * @param keyChar the character the 43-character key thumbprint repeats
   * @param lastSeenAt when it was last seen
   * @param revokedAt when the member disconnected it, or {@code null}
   * @return the installation's id
   */
  private @NotNull UUID installation(
      @NotNull ExchangeClient client,
      @NotNull UUID owner,
      char keyChar,
      @NotNull Instant lastSeenAt,
      @Nullable Instant revokedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO exchange_installation (id, exchange_client_id, user_id, key_thumbprint,
            label, first_seen_at, last_seen_at, revoked_at, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        client.getId(),
        owner,
        String.valueOf(keyChar).repeat(43),
        "PC " + keyChar,
        Timestamp.from(days(120)),
        Timestamp.from(lastSeenAt),
        revokedAt == null ? null : Timestamp.from(revokedAt),
        Timestamp.from(days(120)));
    return id;
  }
}
