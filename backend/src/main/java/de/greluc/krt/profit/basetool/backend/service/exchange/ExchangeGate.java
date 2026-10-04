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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientRevocation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.support.SubjectAuthentication;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The backend's own check of an exchange request, used as {@code @exchangeGate} in {@code
 * PreAuthorize} on every exchange controller method (REQ-XCH-004): the caller is an acting member
 * relayed for a registry client, the global switch is on, the client is active, the member has not
 * disconnected the installation or, after the connection was made, the client (REQ-XCH-008), and
 * the needed capability was both relayed and granted in the registry. Every refusal is counted; a
 * relayed request is refused with the code and status the gateway's own gate answers for the same
 * situation (REQ-XCH-025), so a client sees one code whichever side refuses it.
 */
@Slf4j
@Component("exchangeGate")
@RequiredArgsConstructor
public class ExchangeGate {

  /** Refusal reason: the caller is not an acting member relayed for an external client. */
  static final String REASON_NOT_RELAYED = "not_relayed";

  /** Refusal reason: the global exchange switch is off. */
  static final String REASON_SWITCH_OFF = "switch_off";

  /** Refusal reason: the relayed client is not in the registry. */
  static final String REASON_CLIENT_UNKNOWN = "client_unknown";

  /** Refusal reason: the relayed client is suspended. */
  static final String REASON_CLIENT_SUSPENDED = "client_suspended";

  /** Refusal reason: the needed capability was not relayed or not granted to the client. */
  static final String REASON_SCOPE_MISSING = "scope_missing";

  /** Refusal reason: the member disconnected the calling installation. */
  static final String REASON_INSTALLATION_REVOKED = "installation_revoked";

  /** Refusal reason: the member disconnected the client after the relayed connection was made. */
  static final String REASON_CLIENT_REVOKED = "client_revoked";

  /** Refusal reason: the mirrored client revocations could not be read, so none is assumed. */
  static final String REASON_REVOCATIONS_UNREADABLE = "revocations_unreadable";

  private final ExchangeClientRepository clientRepository;
  private final ExchangeSettingsRepository settingsRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeClientRevocationRepository clientRevocationRepository;
  private final ExchangeRevocationMirror revocationMirror;
  private final MeterRegistry meterRegistry;

  /** Registers the refusal counter for every reason at zero, so a first refusal is an increase. */
  @PostConstruct
  void registerRefusalCounters() {
    for (String reason :
        new String[] {
          REASON_NOT_RELAYED,
          REASON_SWITCH_OFF,
          REASON_CLIENT_UNKNOWN,
          REASON_CLIENT_SUSPENDED,
          REASON_SCOPE_MISSING,
          REASON_INSTALLATION_REVOKED,
          REASON_CLIENT_REVOKED,
          REASON_REVOCATIONS_UNREADABLE
        }) {
      meterRegistry.counter(MetricNames.EXCHANGE_GATE_REFUSED, MetricNames.TAG_REASON, reason);
    }
  }

  /**
   * Allows a request that needs one capability.
   *
   * @param scope the capability's OAuth scope
   * @param authentication the current authentication
   * @return {@code true} when every condition holds, {@code false} when the caller is not a relayed
   *     acting member
   * @throws ExchangeProblemException with the gateway's code for a relayed request the registry,
   *     the revocations or the grants refuse
   */
  @Transactional(readOnly = true)
  public boolean allows(@NotNull String scope, @Nullable Authentication authentication) {
    Optional<Set<String>> usable = usableScopes(authentication);
    if (usable.isEmpty()) {
      return false;
    }
    if (!usable.get().contains(scope)) {
      throw refuse(REASON_SCOPE_MISSING, ExchangeProblemException.scopeMissing());
    }
    return true;
  }

  /**
   * Allows a request that any exchange capability serves.
   *
   * @param authentication the current authentication
   * @return {@code true} when every condition holds for at least one capability, {@code false} when
   *     the caller is not a relayed acting member
   * @throws ExchangeProblemException with the gateway's code for a relayed request the registry,
   *     the revocations or the grants refuse
   */
  @Transactional(readOnly = true)
  public boolean allowsAny(@Nullable Authentication authentication) {
    Optional<Set<String>> usable = usableScopes(authentication);
    if (usable.isEmpty()) {
      return false;
    }
    if (usable.get().isEmpty()) {
      throw refuse(REASON_SCOPE_MISSING, ExchangeProblemException.scopeMissing());
    }
    return true;
  }

  /**
   * Returns the scopes both relayed and granted, after the relay, switch and client checks.
   *
   * @param authentication the current authentication
   * @return the usable scopes, or empty after a counted refusal of a caller that is not relayed
   * @throws ExchangeProblemException for a relayed request the switch, the registry or a revocation
   *     refuses
   */
  @NotNull
  private Optional<Set<String>> usableScopes(@Nullable Authentication authentication) {
    if (!(authentication instanceof SubjectAuthentication subject)
        || subject.externalClient() == null
        || authentication.getAuthorities().stream()
            .noneMatch(a -> Roles.authority(Roles.EXCHANGE_MEMBER).equals(a.getAuthority()))) {
      count(REASON_NOT_RELAYED);
      return Optional.empty();
    }
    boolean enabled =
        settingsRepository
            .findById(ExchangeSettings.SINGLETON_ID)
            .map(ExchangeSettings::isEnabled)
            .orElse(false);
    if (!enabled) {
      throw refuse(REASON_SWITCH_OFF, ExchangeProblemException.exchangeDisabled());
    }
    ExchangeClient client =
        clientRepository
            .findWithCapabilitiesByClientId(subject.externalClient())
            .orElseThrow(
                () -> refuse(REASON_CLIENT_UNKNOWN, ExchangeProblemException.clientNotAllowed()));
    if (client.getStatus() != ExchangeClientStatus.ACTIVE) {
      throw refuse(REASON_CLIENT_SUSPENDED, ExchangeProblemException.clientSuspended());
    }
    if (installationRevoked(subject)) {
      throw refuse(REASON_INSTALLATION_REVOKED, ExchangeProblemException.installationRevoked());
    }
    if (clientRevoked(subject, client)) {
      throw refuse(REASON_CLIENT_REVOKED, ExchangeProblemException.clientRevoked());
    }
    Set<String> granted =
        client.getCapabilities().stream()
            .map(ExchangeCapability::getScope)
            .collect(Collectors.toSet());
    Set<String> relayed =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith(Roles.EXCHANGE_CAPABILITY_PREFIX))
            .map(a -> a.substring(Roles.EXCHANGE_CAPABILITY_PREFIX.length()))
            .filter(granted::contains)
            .collect(Collectors.toSet());
    return Optional.of(relayed);
  }

  /**
   * Tells whether the calling installation was disconnected by the member.
   *
   * @param subject the acting member's authentication
   * @return {@code true} when the installation is known and revoked
   */
  private boolean installationRevoked(@NotNull SubjectAuthentication subject) {
    String key = subject.exchangeInstallationKey();
    if (key == null) {
      return false;
    }
    return installationRepository
        .findByKey(subject.externalClient(), UUID.fromString(subject.subject()), key)
        .map(ExchangeInstallation::getRevokedAt)
        .isPresent();
  }

  /**
   * Tells whether the member disconnected the client at or after the second of the relayed
   * connection time, the time the gateway compared (REQ-XCH-008), taking the later of the stored
   * revocation and the mirror the gateway reads; a request relayed without a connection time counts
   * as connected before any disconnect.
   *
   * @param subject the acting member's authentication, relayed for an external client
   * @param client the relayed client's registry entry
   * @return {@code true} when a disconnect covers the token
   * @throws ExchangeProblemException {@code 503 REGISTRY_UNAVAILABLE} when the mirror cannot be
   *     read, so the request fails closed
   */
  private boolean clientRevoked(
      @NotNull SubjectAuthentication subject, @NotNull ExchangeClient client) {
    UUID member = UUID.fromString(subject.subject());
    Instant mirrored;
    try {
      mirrored =
          revocationMirror.revokedAt(Objects.requireNonNull(subject.externalClient()), member);
    } catch (RuntimeException e) {
      log.warn("The exchange revocations could not be read: {}", e.getClass().getSimpleName());
      throw refuse(REASON_REVOCATIONS_UNREADABLE, ExchangeProblemException.registryUnavailable(e));
    }
    Instant stored =
        clientRevocationRepository
            .findById(new ExchangeClientRevocation.Key(client.getId(), member))
            .map(ExchangeClientRevocation::getRevokedAt)
            .orElse(null);
    Instant revokedAt = later(mirrored, stored);
    if (revokedAt == null) {
      return false;
    }
    Long connectedAt = subject.exchangeConnectedAt();
    return connectedAt == null || connectedAt <= revokedAt.getEpochSecond();
  }

  /**
   * Returns the later of two optional points in time.
   *
   * @param first one time, or {@code null}
   * @param second the other time, or {@code null}
   * @return the later one, or {@code null} when both are absent
   */
  private static @Nullable Instant later(@Nullable Instant first, @Nullable Instant second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    return first.isAfter(second) ? first : second;
  }

  /**
   * Counts a refusal and returns the problem to throw for it.
   *
   * @param reason the bounded reason
   * @param problem the refusal with the gateway's code for the same situation
   * @return {@code problem}
   */
  private @NotNull ExchangeProblemException refuse(
      @NotNull String reason, @NotNull ExchangeProblemException problem) {
    count(reason);
    return problem;
  }

  /**
   * Counts a refusal.
   *
   * @param reason the bounded reason
   */
  private void count(@NotNull String reason) {
    meterRegistry
        .counter(MetricNames.EXCHANGE_GATE_REFUSED, MetricNames.TAG_REASON, reason)
        .increment();
  }
}
