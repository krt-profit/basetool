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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeInstallationConnectedEvent;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeInstallationDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The installations of the exchange: created on first sight, labelled by the client, and seen again
 * at most every {@link #TOUCH_INTERVAL} (REQ-XCH-007).
 */
@Service
@RequiredArgsConstructor
public class ExchangeInstallationService {

  /** How often a busy installation's last-seen time is written. */
  static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

  /**
   * The label rule of the published schema: letters, digits, space, {@code -}, {@code _} and {@code
   * .}, at most 40, not starting with a space.
   */
  static final Pattern LABEL = Pattern.compile("^[\\p{L}\\p{N}._-][\\p{L}\\p{N} ._-]{0,39}$");

  private final ExchangeInstallationRepository installationRepository;

  /** Names the client in the new-connection notification. */
  private final ExchangeClientRepository clientRepository;

  /** Publishes the new-connection event, which the notification engine handles after commit. */
  private final ApplicationEventPublisher eventPublisher;

  /** Counts the installations created, for the installation-surge alert. */
  private final MeterRegistry meterRegistry;

  private final Clock clock = Clock.systemUTC();

  /**
   * Records that an installation was seen, in its own transaction so a failed request still counts.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @param keyThumbprint the DPoP key thumbprint
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void touch(@NotNull String clientId, @NotNull UUID member, @NotNull String keyThumbprint) {
    record(clientId, member, keyThumbprint);
  }

  /**
   * Upserts the installation and, when this call created it, announces the new connection to the
   * member (REQ-XCH-032); the upsert decides, so two concurrent first calls announce it once.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @param keyThumbprint the DPoP key thumbprint
   */
  private void record(
      @NotNull String clientId, @NotNull UUID member, @NotNull String keyThumbprint) {
    Instant now = clock.instant();
    for (ExchangeInstallationRepository.Touched touched :
        installationRepository.touch(
            clientId, member, keyThumbprint, now, now.minus(TOUCH_INTERVAL))) {
      if (touched.getInserted()) {
        meterRegistry
            .counter(
                MetricNames.EXCHANGE_INSTALLATIONS_CREATED, MetricNames.TAG_CLIENT_ID, clientId)
            .increment();
        String clientName =
            clientRepository
                .findWithCapabilitiesByClientId(clientId)
                .map(ExchangeClient::getDisplayName)
                .orElse(clientId);
        eventPublisher.publishEvent(
            new ExchangeInstallationConnectedEvent(member, touched.getId(), clientName));
      }
    }
  }

  /**
   * Returns the calling installation, creating it on first sight.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @param keyThumbprint the DPoP key thumbprint
   * @return the installation
   */
  @NotNull
  @Transactional
  public ExchangeInstallationDto current(
      @NotNull String clientId, @NotNull UUID member, @NotNull String keyThumbprint) {
    return toDto(load(clientId, member, keyThumbprint));
  }

  /**
   * Sets the calling installation's label.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @param keyThumbprint the DPoP key thumbprint
   * @param label the new label
   * @return the installation
   * @throws BadRequestException when the label breaks the rule
   */
  @NotNull
  @Transactional
  public ExchangeInstallationDto label(
      @NotNull String clientId,
      @NotNull UUID member,
      @NotNull String keyThumbprint,
      @Nullable String label) {
    String normalised = normaliseLabel(label);
    ExchangeInstallation installation = load(clientId, member, keyThumbprint);
    installation.setLabel(normalised);
    return toDto(installationRepository.saveAndFlush(installation));
  }

  /**
   * Validates and normalises a label to NFC.
   *
   * @param label the raw label
   * @return the normalised label
   * @throws BadRequestException when it is missing or breaks the rule
   */
  @NotNull
  static String normaliseLabel(@Nullable String label) {
    if (label == null) {
      throw new BadRequestException("error.exchange.installation.label");
    }
    String normalised = Normalizer.normalize(label, Normalizer.Form.NFC);
    if (!LABEL.matcher(normalised).matches()) {
      throw new BadRequestException("error.exchange.installation.label");
    }
    return normalised;
  }

  /**
   * Loads the installation, creating it first when needed.
   *
   * @param clientId the Keycloak client id
   * @param member the member
   * @param keyThumbprint the DPoP key thumbprint
   * @return the installation
   */
  @NotNull
  private ExchangeInstallation load(
      @NotNull String clientId, @NotNull UUID member, @NotNull String keyThumbprint) {
    record(clientId, member, keyThumbprint);
    return installationRepository
        .findByKey(clientId, member, keyThumbprint)
        .orElseThrow(() -> new IllegalStateException("installation missing after upsert"));
  }

  /**
   * Maps an installation.
   *
   * @param installation the entity
   * @return the DTO
   */
  @NotNull
  private static ExchangeInstallationDto toDto(@NotNull ExchangeInstallation installation) {
    return new ExchangeInstallationDto(
        installation.getId().toString(),
        installation.getLabel(),
        installation.getFirstSeenAt(),
        installation.getLastSeenAt());
  }
}
