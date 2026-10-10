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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRevocationRow;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's own view and control of the exchange connections (REQ-XCH-008, REQ-XCH-032): which
 * clients and installations are connected, and disconnecting one installation or a whole client. A
 * disconnect reaches the gateway's mirror before the commit or not at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectedAppsService {

  /** The {@code kind} label of a disconnected installation. */
  static final String KIND_INSTALLATION = "installation";

  /** The {@code kind} label of a disconnected client. */
  static final String KIND_CLIENT = "client";

  private final ExchangeClientRepository clientRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeClientRevocationRepository revocationRepository;
  private final ExchangeRevocationMirror revocationMirror;
  private final KeycloakService keycloakService;
  private final AuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;

  /** Reads and clears the new-connection notifications that mark an installation unseen. */
  private final NotificationRepository notificationRepository;

  private final ExchangeJournalRepository journalRepository;
  private final ExchangeEntryLabels entryLabels;

  private final Clock clock = Clock.systemUTC();

  /** Registers the disconnect counter for both kinds at zero. */
  @PostConstruct
  void registerDisconnectCounters() {
    meterRegistry.counter(MetricNames.EXCHANGE_DISCONNECTS, MetricNames.TAG_KIND, KIND_CLIENT);
    meterRegistry.counter(
        MetricNames.EXCHANGE_DISCONNECTS, MetricNames.TAG_KIND, KIND_INSTALLATION);
  }

  /**
   * Lists the member's connected clients with their live installations; an installation last seen
   * before its client was disconnected counts as gone.
   *
   * @param member the member
   * @return the connected clients, by client id
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<ConnectedAppDto> list(@NotNull UUID member) {
    Map<String, Instant> revokedAt =
        revocationRepository.findAllByUserId(member).stream()
            .collect(
                Collectors.toMap(
                    ExchangeRevocationRow::clientId, ExchangeRevocationRow::revokedAt));
    Map<String, List<ExchangeInstallation>> byClient = new LinkedHashMap<>();
    for (ExchangeInstallation installation : installationRepository.findAllByUserId(member)) {
      Instant cut = revokedAt.get(installation.getClient().getClientId());
      if (installation.getRevokedAt() != null
          || (cut != null && !installation.getLastSeenAt().isAfter(cut))) {
        continue;
      }
      byClient
          .computeIfAbsent(installation.getClient().getClientId(), _ -> new ArrayList<>())
          .add(installation);
    }
    Set<UUID> unseen =
        Set.copyOf(
            notificationRepository.findUnreadEntityIds(
                member, NotificationType.EXCHANGE_INSTALLATION_CONNECTED));
    Map<String, List<ExchangeJournalEntry>> writes = new LinkedHashMap<>();
    List<ExchangeJournalEntry> allWrites = new ArrayList<>();
    for (String clientId : byClient.keySet()) {
      List<ExchangeJournalEntry> latest =
          journalRepository.findTop10ByUserIdAndClientIdOrderByRecordedAtDescIdDesc(
              member, clientId);
      writes.put(clientId, latest);
      allWrites.addAll(latest);
    }
    Map<UUID, String> labels = entryLabels.label(allWrites);
    List<ConnectedAppDto> apps = new ArrayList<>();
    for (List<ExchangeInstallation> installations : byClient.values()) {
      ExchangeClient client = installations.getFirst().getClient();
      apps.add(
          new ConnectedAppDto(
              client.getClientId(),
              client.getDisplayName(),
              client.getCapabilities().stream().sorted().map(ExchangeCapability::getScope).toList(),
              installations.stream()
                  .map(
                      i ->
                          new ConnectedInstallationDto(
                              i.getId(),
                              i.getLabel(),
                              i.getFirstSeenAt(),
                              i.getLastSeenAt(),
                              unseen.contains(i.getId())))
                  .toList(),
              writes.get(client.getClientId()).stream()
                  .map(
                      w ->
                          new ConnectedAppActivityDto(
                              w.getRecordedAt(),
                              w.getResource().name(),
                              w.getAction().name(),
                              labels.get(w.getId()),
                              w.getUndoneAt() != null))
                  .toList()));
    }
    apps.sort((a, b) -> a.clientId().compareTo(b.clientId()));
    return apps;
  }

  /**
   * Marks the member's new-connection notification for one installation read, which ends that
   * installation's highlight; nothing else changes, so it is not audited.
   *
   * @param member the member
   * @param installationId the installation the member acknowledged
   * @return how many notifications were marked; {@code 0} for another member's installation
   */
  @Transactional
  public int markSeen(@NotNull UUID member, @NotNull UUID installationId) {
    return notificationRepository.markReadOfTypeAndEntity(
        member, NotificationType.EXCHANGE_INSTALLATION_CONNECTED, installationId, clock.instant());
  }

  /**
   * Disconnects one of the member's installations: its key is denied in the mirror, then marked
   * revoked, so every token bound to it is refused and reconnecting needs a new key.
   *
   * @param member the member
   * @param installationId the installation
   * @throws ExternalServiceException when the mirror could not be written
   */
  @Transactional
  public void disconnectInstallation(@NotNull UUID member, @NotNull UUID installationId) {
    ExchangeInstallation installation =
        Entities.require(
            installationRepository.findById(installationId).filter(i -> ownedBy(i, member)),
            "Installation not found");
    if (installation.getRevokedAt() != null) {
      return;
    }
    Instant now = clock.instant();
    mirror(() -> revocationMirror.deny(installation.getKeyThumbprint(), now));
    installation.setRevokedAt(now);
    installationRepository.saveAndFlush(installation);
    auditRecorder.record(
        AuditEventType.EXCHANGE_INSTALLATION_DISCONNECTED,
        installation.getId(),
        installation.getClient().getClientId(),
        member,
        null);
    count(KIND_INSTALLATION);
  }

  /**
   * Disconnects a whole client for the member: Keycloak first removes the member's consent for the
   * client with its offline sessions and ends the client in every online session; then the
   * revocation time, read afterwards, reaches the mirror and is stored. The gateway so refuses
   * every token of an earlier connection, and a connection made in a later second works
   * (REQ-XCH-008).
   *
   * @param member the member
   * @param clientId the Keycloak client id
   * @return the time the revocation was stamped with, which the caller waits out after the commit
   *     ({@link ExchangeRevocationSecond})
   * @throws ExternalServiceException when Keycloak or the mirror could not be reached
   */
  @Transactional
  public @NotNull Instant disconnectClient(@NotNull UUID member, @NotNull String clientId) {
    ExchangeClient client =
        Entities.require(
            clientRepository.findWithCapabilitiesByClientId(clientId), "Exchange client not found");
    endSessions(member, clientId);
    Instant now = clock.instant();
    mirror(() -> revocationMirror.revoke(clientId, member, now));
    revocationRepository.upsert(client.getId(), member, now);
    auditRecorder.record(
        AuditEventType.EXCHANGE_CLIENT_DISCONNECTED,
        client.getId(),
        client.getClientId(),
        member,
        AuditDetails.of("by", "member"));
    count(KIND_CLIENT);
    return now;
  }

  /**
   * Removes the member's Keycloak consent for the client with its offline sessions, deletes the
   * online sessions only the client holds, and ends the client inside the sessions it shares with
   * other clients, which stay signed in.
   *
   * @param member the member
   * @param clientId the Keycloak client id
   * @throws ExternalServiceException when Keycloak could not be reached
   */
  private void endSessions(@NotNull UUID member, @NotNull String clientId) {
    try {
      keycloakService.revokeConsent(member, clientId);
      if (keycloakService.endSessionsHeldOnlyBy(member, clientId) > 0) {
        keycloakService.endClientInSharedSessions(member, clientId);
      }
    } catch (RuntimeException e) {
      throw new ExternalServiceException("The client's consent or sessions could not be ended", e);
    }
  }

  /**
   * Tells whether an installation belongs to the member.
   *
   * @param installation the installation
   * @param member the member
   * @return {@code true} when it is the member's
   */
  private static boolean ownedBy(@NotNull ExchangeInstallation installation, @NotNull UUID member) {
    return member.equals(installation.getUser().getId());
  }

  /**
   * Writes to the mirror before the commit, failing the action when it cannot.
   *
   * @param write the write
   * @throws ExternalServiceException when the write failed
   */
  private void mirror(@NotNull Runnable write) {
    try {
      write.run();
    } catch (RuntimeException e) {
      throw new ExternalServiceException("The exchange revocation could not be mirrored", e);
    }
  }

  /**
   * Counts one disconnect.
   *
   * @param kind {@code client} or {@code installation}
   */
  private void count(@NotNull String kind) {
    meterRegistry.counter(MetricNames.EXCHANGE_DISCONNECTS, MetricNames.TAG_KIND, kind).increment();
  }
}
