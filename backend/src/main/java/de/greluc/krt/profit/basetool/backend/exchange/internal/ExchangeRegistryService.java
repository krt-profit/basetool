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
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manages the exchange client registry and the global switch for {@code ADMIN} (REQ-XCH-003): each
 * change takes the settings row lock, is mirrored by {@link ExchangeRegistryMirrorSync}, audited in
 * „Verbundene Anwendungen" and counted for {@code ExchangeRegistryChanged}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRegistryService {

  private final ExchangeClientRepository clientRepository;
  private final ExchangeRegistryMirrorSync mirrorSync;
  private final AuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;
  private final KnownExchangeClients knownExchangeClients;

  /** Counts each client's live installations for the admin page. */
  private final ExchangeInstallationRepository installationRepository;

  /** The Basetool's own client ids, which the registry refuses. */
  private final FirstPartyClientIds firstPartyClientIds;

  /**
   * Registers {@code basetool_exchange_registry_changes_total} for every action at zero, so the
   * first change of each kind shows up as an increase.
   */
  @PostConstruct
  void registerChangeCounters() {
    for (ExchangeRegistryAction action : ExchangeRegistryAction.values()) {
      meterRegistry.counter(
          MetricNames.EXCHANGE_REGISTRY_CHANGES, MetricNames.TAG_ACTION, action.getTag());
    }
  }

  /**
   * Lists every registry client with its capabilities.
   *
   * @return all clients, ordered by client id
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<ExchangeClient> listClients() {
    return clientRepository.findAllWithCapabilities();
  }

  /**
   * Returns how widely each client is in use: its connected members and last activity, counted over
   * live installations only. A client nobody uses has no row.
   *
   * @return one row per client in use
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<ExchangeClientUsageDto> usage() {
    return installationRepository.countLiveByClient().stream()
        .map(u -> new ExchangeClientUsageDto(u.getId(), u.getConnectedMembers(), u.getLastSeenAt()))
        .toList();
  }

  /**
   * Loads one registry client with its capabilities.
   *
   * @param id the registry id
   * @return the client
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when absent
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeClient getClient(@NotNull UUID id) {
    return Entities.require(
        clientRepository.findWithCapabilitiesById(id), "Exchange client not found");
  }

  /**
   * Reads the global switch.
   *
   * @return the settings row
   */
  @NotNull
  @Transactional(readOnly = true)
  public ExchangeSettings getSettings() {
    return mirrorSync.currentSettings();
  }

  /**
   * Registers a client as {@code ACTIVE}; the mirror learns of it after the commit.
   *
   * @param request the new client
   * @return the saved client
   * @throws DuplicateEntityException when the client id is already registered
   * @throws BadRequestException when {@code exchange.connect} is missing, the client id is one of
   *     the Basetool's own, or the display name breaks {@link ExchangeDisplayNames}
   */
  @NotNull
  @Transactional
  public ExchangeClient createClient(@NotNull ExchangeClientCreateRequest request) {
    mirrorSync.lockSettings();
    requireConnect(request.capabilities());
    if (firstPartyClientIds.contains(request.clientId())) {
      throw new BadRequestException("error.exchange.client.reserved");
    }
    String displayName = requireDisplayName(request.displayName());
    if (clientRepository.existsByClientId(request.clientId())) {
      throw new DuplicateEntityException("error.exchange.client.taken");
    }
    final ExchangeRegistrySnapshot before = mirrorSync.load();
    ExchangeClient client = new ExchangeClient();
    client.setClientId(request.clientId());
    client.setDisplayName(displayName);
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.copyOf(request.capabilities()));
    client.setMinClientVersion(StringNormalization.trimToNull(request.minClientVersion()));
    client.setContactUrl(StringNormalization.trimToNull(request.contactUrl()));
    client.setRequestsPerMinute(request.requestsPerMinute());
    client.setWritesPerDay(request.writesPerDay());
    ExchangeClient saved = clientRepository.saveAndFlush(client);
    mirrorSync.mirrorChange(before, mirrorSync.load());
    auditRecorder.record(
        AuditEventType.EXCHANGE_CLIENT_CREATED,
        saved.getId(),
        saved.getClientId(),
        null,
        AuditDetails.of("capabilities", scopes(saved.getCapabilities())));
    countAfterCommit(ExchangeRegistryAction.CREATED);
    return saved;
  }

  /**
   * Replaces a client's editable fields; removing a capability is mirrored before the commit.
   *
   * @param id the registry id
   * @param request the new values and the version last seen
   * @return the saved client
   * @throws BadRequestException when {@code exchange.connect} is missing or the display name breaks
   *     {@link ExchangeDisplayNames}
   */
  @NotNull
  @Transactional
  public ExchangeClient updateClient(
      @NotNull UUID id, @NotNull ExchangeClientUpdateRequest request) {
    mirrorSync.lockSettings();
    requireConnect(request.capabilities());
    String displayName = requireDisplayName(request.displayName());
    ExchangeClient client = getClient(id);
    OptimisticLock.check(client.getVersion(), request.version(), ExchangeClient.class, id);
    final ExchangeRegistrySnapshot before = mirrorSync.load();
    Set<ExchangeCapability> oldCapabilities = EnumSet.noneOf(ExchangeCapability.class);
    oldCapabilities.addAll(client.getCapabilities());
    Set<ExchangeCapability> newCapabilities = EnumSet.copyOf(request.capabilities());
    List<String> changed = new ArrayList<>();
    if (!displayName.equals(client.getDisplayName())) {
      client.setDisplayName(displayName);
      changed.add("displayName");
    }
    if (!oldCapabilities.equals(newCapabilities)) {
      client.getCapabilities().clear();
      client.getCapabilities().addAll(newCapabilities);
      changed.add("capabilities");
    }
    changed.addAll(applyLimits(client, request));
    if (changed.isEmpty()) {
      return client;
    }
    final ExchangeClient saved = clientRepository.saveAndFlush(client);
    mirrorSync.mirrorChange(before, mirrorSync.load());
    Set<ExchangeCapability> added = EnumSet.copyOf(newCapabilities);
    added.removeAll(oldCapabilities);
    Set<ExchangeCapability> removed = EnumSet.copyOf(oldCapabilities);
    removed.removeAll(newCapabilities);
    auditRecorder.record(
        AuditEventType.EXCHANGE_CLIENT_UPDATED,
        saved.getId(),
        saved.getClientId(),
        null,
        AuditDetails.of("changed", String.join(",", changed))
            .with("added", scopes(added))
            .with("removed", scopes(removed)));
    countAfterCommit(ExchangeRegistryAction.UPDATED);
    return saved;
  }

  /**
   * Suspends a client; the suspension reaches the mirror before the commit, and the change fails
   * when it cannot.
   *
   * @param id the registry id
   * @param version the version last seen
   * @return the saved client, unchanged when it was already suspended
   */
  @NotNull
  @Transactional
  public ExchangeClient suspendClient(@NotNull UUID id, @NotNull Long version) {
    return changeStatus(id, version, ExchangeClientStatus.SUSPENDED);
  }

  /**
   * Activates a suspended client; the mirror learns of it after the commit.
   *
   * @param id the registry id
   * @param version the version last seen
   * @return the saved client, unchanged when it was already active
   */
  @NotNull
  @Transactional
  public ExchangeClient activateClient(@NotNull UUID id, @NotNull Long version) {
    return changeStatus(id, version, ExchangeClientStatus.ACTIVE);
  }

  /**
   * Turns the global switch on or off; switching off reaches the mirror before the commit.
   *
   * @param enabled the new state
   * @param version the version last seen
   * @return the saved settings, unchanged when the switch already had that state
   */
  @NotNull
  @Transactional
  public ExchangeSettings updateSettings(boolean enabled, @NotNull Long version) {
    ExchangeSettings settings = mirrorSync.lockSettings();
    OptimisticLock.check(
        settings.getVersion(), version, ExchangeSettings.class, ExchangeSettings.SINGLETON_ID);
    if (settings.isEnabled() == enabled) {
      return settings;
    }
    ExchangeRegistrySnapshot before = mirrorSync.load();
    settings.setEnabled(enabled);
    final ExchangeSettings saved = mirrorSync.saveSettings(settings);
    mirrorSync.mirrorChange(before, mirrorSync.load());
    auditRecorder.record(
        AuditEventType.EXCHANGE_SWITCH_CHANGED,
        null,
        null,
        null,
        AuditDetails.of("enabled", enabled));
    countAfterCommit(
        enabled ? ExchangeRegistryAction.SWITCH_ON : ExchangeRegistryAction.SWITCH_OFF);
    return saved;
  }

  /**
   * Sets a client's status under the lock and mirrors, audits and counts the change.
   *
   * @param id the registry id
   * @param version the version last seen
   * @param status the target status
   * @return the saved client
   */
  @NotNull
  private ExchangeClient changeStatus(
      @NotNull UUID id, @NotNull Long version, @NotNull ExchangeClientStatus status) {
    mirrorSync.lockSettings();
    ExchangeClient client = getClient(id);
    OptimisticLock.check(client.getVersion(), version, ExchangeClient.class, id);
    if (client.getStatus() == status) {
      return client;
    }
    ExchangeRegistrySnapshot before = mirrorSync.load();
    client.setStatus(status);
    ExchangeClient saved = clientRepository.saveAndFlush(client);
    mirrorSync.mirrorChange(before, mirrorSync.load());
    boolean suspended = status == ExchangeClientStatus.SUSPENDED;
    auditRecorder.record(
        suspended
            ? AuditEventType.EXCHANGE_CLIENT_SUSPENDED
            : AuditEventType.EXCHANGE_CLIENT_ACTIVATED,
        saved.getId(),
        saved.getClientId(),
        null,
        null);
    countAfterCommit(
        suspended ? ExchangeRegistryAction.SUSPENDED : ExchangeRegistryAction.ACTIVATED);
    return saved;
  }

  /**
   * Copies the version floor, the contact and the limit overrides onto the client.
   *
   * @param client the client to change
   * @param request the new values
   * @return the names of the fields that changed
   */
  @NotNull
  private static List<String> applyLimits(
      @NotNull ExchangeClient client, @NotNull ExchangeClientUpdateRequest request) {
    List<String> changed = new ArrayList<>();
    String minClientVersion = StringNormalization.trimToNull(request.minClientVersion());
    if (!Objects.equals(minClientVersion, client.getMinClientVersion())) {
      client.setMinClientVersion(minClientVersion);
      changed.add("minClientVersion");
    }
    String contactUrl = StringNormalization.trimToNull(request.contactUrl());
    if (!Objects.equals(contactUrl, client.getContactUrl())) {
      client.setContactUrl(contactUrl);
      changed.add("contactUrl");
    }
    if (!Objects.equals(request.requestsPerMinute(), client.getRequestsPerMinute())) {
      client.setRequestsPerMinute(request.requestsPerMinute());
      changed.add("requestsPerMinute");
    }
    if (!Objects.equals(request.writesPerDay(), client.getWritesPerDay())) {
      client.setWritesPerDay(request.writesPerDay());
      changed.add("writesPerDay");
    }
    return changed;
  }

  /**
   * Refuses a capability set without the base capability.
   *
   * @param capabilities the requested capabilities
   * @throws BadRequestException when {@code exchange.connect} is missing
   */
  private static void requireConnect(@NotNull Set<ExchangeCapability> capabilities) {
    if (!capabilities.contains(ExchangeCapability.CONNECT)) {
      throw new BadRequestException("error.exchange.client.connectRequired");
    }
  }

  /**
   * Strips a display name and refuses one that breaks {@link ExchangeDisplayNames}.
   *
   * @param displayName the requested name
   * @return the stripped name
   * @throws BadRequestException naming the broken rule
   */
  private static @NotNull String requireDisplayName(@NotNull String displayName) {
    String stripped = displayName.strip();
    ExchangeDisplayNames.violation(stripped)
        .ifPresent(
            key -> {
              throw new BadRequestException(key);
            });
    return stripped;
  }

  /**
   * Joins capabilities as their scopes in declaration order.
   *
   * @param capabilities the capabilities
   * @return the comma-separated scopes, empty for none
   */
  @NotNull
  private static String scopes(@NotNull Set<ExchangeCapability> capabilities) {
    return capabilities.stream()
        .sorted()
        .map(ExchangeCapability::getScope)
        .collect(Collectors.joining(","));
  }

  /**
   * Counts a registry change once its transaction commits.
   *
   * @param action the kind of change
   */
  private void countAfterCommit(@NotNull ExchangeRegistryAction action) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            knownExchangeClients.invalidate();
            meterRegistry
                .counter(
                    MetricNames.EXCHANGE_REGISTRY_CHANGES, MetricNames.TAG_ACTION, action.getTag())
                .increment();
            log.info("Exchange registry changed: {}", action.getTag());
          }
        });
  }
}
