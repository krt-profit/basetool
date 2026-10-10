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
import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ends a departed member's exchange access at once (REQ-XCH-008): the member's consent for every
 * registry client is removed and the member is logged out of every session, which makes their
 * offline tokens stale; only then is the revocation time read, and it reaches the mirror and the
 * database for every client, also when a Keycloak step failed. Each step is attempted on its own; a
 * failed one is logged and counted, never thrown back into the roster sync.
 */
@Slf4j
@Service
public class ExchangeDepartureService {

  /** The {@code outcome} label of a departure every step of which succeeded. */
  static final String OUTCOME_DONE = "done";

  /** The {@code outcome} label of a departure one step of which failed. */
  static final String OUTCOME_FAILED = "failed";

  private final ExchangeClientRepository clientRepository;
  private final ExchangeClientRevocationRepository revocationRepository;
  private final ExchangeRevocationMirror revocationMirror;
  private final KeycloakService keycloakService;
  private final AuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;
  private final TransactionTemplate requiresNew;
  private final Clock clock = Clock.systemUTC();

  /**
   * Creates the service.
   *
   * @param clientRepository the registry
   * @param revocationRepository the per-member client revocations
   * @param revocationMirror the gateway's revocation mirror
   * @param keycloakService the identity provider's admin API
   * @param auditRecorder the audit trail
   * @param meterRegistry the registry the departure counter binds to
   * @param transactionManager the manager the writes run in, after the sync's own commit
   */
  public ExchangeDepartureService(
      @NotNull ExchangeClientRepository clientRepository,
      @NotNull ExchangeClientRevocationRepository revocationRepository,
      @NotNull ExchangeRevocationMirror revocationMirror,
      @NotNull KeycloakService keycloakService,
      @NotNull AuditRecorder auditRecorder,
      @NotNull MeterRegistry meterRegistry,
      @NotNull PlatformTransactionManager transactionManager) {
    this.clientRepository = clientRepository;
    this.revocationRepository = revocationRepository;
    this.revocationMirror = revocationMirror;
    this.keycloakService = keycloakService;
    this.auditRecorder = auditRecorder;
    this.meterRegistry = meterRegistry;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Registers the departure counter for both outcomes at zero. */
  @PostConstruct
  void registerDepartureCounters() {
    meterRegistry.counter(MetricNames.EXCHANGE_DEPARTURES, MetricNames.TAG_OUTCOME, OUTCOME_DONE);
    meterRegistry.counter(MetricNames.EXCHANGE_DEPARTURES, MetricNames.TAG_OUTCOME, OUTCOME_FAILED);
  }

  /**
   * Ends the departed member's exchange access once the sync that noticed the departure has
   * committed; with no registry client there is nothing to end.
   *
   * @param event the departure
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onDeparture(@NotNull MemberDepartedEvent event) {
    List<ExchangeClient> clients = clientRepository.findAll();
    if (clients.isEmpty()) {
      return;
    }
    UUID member = event.userId();
    boolean failed = false;
    for (ExchangeClient client : clients) {
      failed |=
          !attempt("consent", () -> keycloakService.revokeConsent(member, client.getClientId()));
    }
    failed |= !attempt("logout", () -> keycloakService.logoutUser(member));
    Instant now = clock.instant();
    for (ExchangeClient client : clients) {
      failed |=
          !attempt("mirror", () -> revocationMirror.revoke(client.getClientId(), member, now));
      failed |=
          !attempt(
              "revocation",
              () ->
                  requiresNew.executeWithoutResult(
                      status -> revocationRepository.upsert(client.getId(), member, now)));
    }
    boolean incomplete = failed;
    attempt(
        "audit",
        () ->
            requiresNew.executeWithoutResult(
                status ->
                    auditRecorder.record(
                        AuditEventType.EXCHANGE_MEMBER_DEPARTED,
                        null,
                        null,
                        member,
                        AuditDetails.of("reason", event.reason())
                            .with("clients", clients.size())
                            .with("complete", !incomplete))));
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_DEPARTURES,
            MetricNames.TAG_OUTCOME,
            failed ? OUTCOME_FAILED : OUTCOME_DONE)
        .increment();
  }

  /**
   * Runs one step, logging a failure without the member's identity.
   *
   * @param step the step's name for the log
   * @param work the step
   * @return {@code true} when it succeeded
   */
  private static boolean attempt(@NotNull String step, @NotNull Runnable work) {
    try {
      work.run();
      return true;
    } catch (RuntimeException e) {
      log.warn("Exchange departure step '{}' failed: {}", step, e.getClass().getName());
      return false;
    }
  }
}
