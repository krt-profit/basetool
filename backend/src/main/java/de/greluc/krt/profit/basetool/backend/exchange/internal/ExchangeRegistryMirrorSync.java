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

import de.greluc.krt.profit.basetool.backend.exception.ExternalServiceException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the registry's Redis mirror in line with the database (REQ-XCH-003): a change that takes
 * access away is mirrored before its commit and fails if the mirror cannot be written, every change
 * is mirrored again after it completes, and the reconcile repairs whatever is left.
 *
 * <p>Every read of the database state for the mirror holds the settings row lock, so a mirror write
 * never overtakes a registry change that is still open.
 */
@Slf4j
@Service
public class ExchangeRegistryMirrorSync {

  private final ExchangeRegistryMirror mirror;
  private final ExchangeClientRepository clientRepository;
  private final ExchangeSettingsRepository settingsRepository;
  private final MeterRegistry meterRegistry;
  private final ExchangeClientGauges clientGauges;
  private final TransactionTemplate requiresNew;

  /**
   * Creates the sync.
   *
   * @param mirror the mirror, active or disabled
   * @param clientRepository the registry rows
   * @param settingsRepository the switch, its lock and the revision counter
   * @param meterRegistry the registry the write counter binds to
   * @param clientGauges the per-status client gauges every read of the registry refreshes
   * @param transactionManager the manager the resync's own transactions run in
   */
  public ExchangeRegistryMirrorSync(
      @NotNull ExchangeRegistryMirror mirror,
      @NotNull ExchangeClientRepository clientRepository,
      @NotNull ExchangeSettingsRepository settingsRepository,
      @NotNull MeterRegistry meterRegistry,
      @NotNull ExchangeClientGauges clientGauges,
      @NotNull PlatformTransactionManager transactionManager) {
    this.mirror = mirror;
    this.clientRepository = clientRepository;
    this.settingsRepository = settingsRepository;
    this.meterRegistry = meterRegistry;
    this.clientGauges = clientGauges;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Registers {@code basetool_exchange_mirror_writes_total} for every phase and outcome at zero, so
   * the first failure shows up as an increase.
   */
  @PostConstruct
  void registerWriteCounters() {
    for (ExchangeMirrorPhase phase : ExchangeMirrorPhase.values()) {
      for (String outcome :
          new String[] {
            MetricNames.OUTCOME_WRITTEN, MetricNames.OUTCOME_UNCHANGED, MetricNames.OUTCOME_FAILED
          }) {
        meterRegistry.counter(
            MetricNames.EXCHANGE_MIRROR_WRITES,
            MetricNames.TAG_PHASE,
            phase.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome);
      }
    }
  }

  /**
   * Takes the settings row lock that serialises every registry change and every mirror write.
   *
   * @return the locked settings row
   * @throws IllegalStateException when the settings row is missing
   */
  @NotNull
  public ExchangeSettings lockSettings() {
    return settingsRepository
        .findByIdForUpdate(ExchangeSettings.SINGLETON_ID)
        .orElseThrow(() -> new IllegalStateException("exchange_settings row 1 is missing"));
  }

  /**
   * Reads the settings row without a lock.
   *
   * @return the settings row
   * @throws IllegalStateException when the settings row is missing
   */
  @NotNull
  public ExchangeSettings currentSettings() {
    return settingsRepository
        .findById(ExchangeSettings.SINGLETON_ID)
        .orElseThrow(() -> new IllegalStateException("exchange_settings row 1 is missing"));
  }

  /**
   * Saves and flushes the settings row.
   *
   * @param settings the changed row
   * @return the saved row
   */
  @NotNull
  public ExchangeSettings saveSettings(@NotNull ExchangeSettings settings) {
    return settingsRepository.saveAndFlush(settings);
  }

  /**
   * Reads the registry as the current transaction sees it, flushing pending changes first.
   *
   * @return the registry content
   */
  @NotNull
  public ExchangeRegistrySnapshot load() {
    clientRepository.flush();
    boolean enabled =
        settingsRepository
            .findById(ExchangeSettings.SINGLETON_ID)
            .map(ExchangeSettings::isEnabled)
            .orElse(false);
    return ExchangeRegistrySnapshot.of(enabled, clientRepository.findAllWithCapabilities());
  }

  /**
   * Mirrors a change from inside its transaction: when the change takes access away, the most
   * restrictive combination of before and after is written now, and a failure aborts the change; in
   * every case the mirror is brought in line again once the transaction has completed.
   *
   * @param before the registry at the start of the change
   * @param after the registry with the change applied
   * @throws ExternalServiceException when a restricting change could not be mirrored
   */
  public void mirrorChange(
      @NotNull ExchangeRegistrySnapshot before, @NotNull ExchangeRegistrySnapshot after) {
    if (mirror.isActive() && ExchangeRegistrySnapshot.restricts(before, after)) {
      try {
        mirror.write(
            ExchangeRegistrySnapshot.restrictiveMerge(before, after),
            settingsRepository.nextRevision());
        count(ExchangeMirrorPhase.PRE_COMMIT, MetricNames.OUTCOME_WRITTEN);
      } catch (RuntimeException e) {
        count(ExchangeMirrorPhase.PRE_COMMIT, MetricNames.OUTCOME_FAILED);
        throw new ExternalServiceException("The exchange registry mirror could not be written", e);
      }
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
              clientGauges.update(after);
            }
            resyncQuietly(
                status == STATUS_COMMITTED
                    ? ExchangeMirrorPhase.POST_COMMIT
                    : ExchangeMirrorPhase.ROLLBACK);
          }
        });
  }

  /**
   * Brings the mirror in line with the committed registry and swallows a failure, which stays
   * counted for the reconcile and the alert.
   *
   * @param phase why the resync runs
   */
  public void resyncQuietly(@NotNull ExchangeMirrorPhase phase) {
    try {
      resync(phase);
    } catch (RuntimeException e) {
      log.warn(
          "Exchange registry mirror resync ({}) failed; the reconcile will retry: {}",
          phase.getTag(),
          e.toString());
    }
  }

  /**
   * Brings the mirror in line with the committed registry, writing only when it differs.
   *
   * @param phase why the resync runs
   * @return {@code true} when the mirror was written
   * @throws RuntimeException when Redis could not be read or written
   */
  public boolean resync(@NotNull ExchangeMirrorPhase phase) {
    if (!mirror.isActive()) {
      return false;
    }
    try {
      Boolean written =
          requiresNew.execute(
              _ -> {
                lockSettings();
                ExchangeRegistrySnapshot truth = load();
                clientGauges.update(truth);
                Optional<ExchangeRegistrySnapshot> mirrored = mirror.read();
                if (mirrored.isPresent() && mirrored.get().equals(truth)) {
                  return false;
                }
                mirror.write(truth, settingsRepository.nextRevision());
                return true;
              });
      boolean wrote = Boolean.TRUE.equals(written);
      count(phase, wrote ? MetricNames.OUTCOME_WRITTEN : MetricNames.OUTCOME_UNCHANGED);
      return wrote;
    } catch (RuntimeException e) {
      count(phase, MetricNames.OUTCOME_FAILED);
      throw e;
    }
  }

  /**
   * Counts one mirror write attempt.
   *
   * @param phase when it ran
   * @param outcome {@code written}, {@code unchanged} or {@code failed}
   */
  private void count(@NotNull ExchangeMirrorPhase phase, @NotNull String outcome) {
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_MIRROR_WRITES,
            MetricNames.TAG_PHASE,
            phase.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome)
        .increment();
  }
}
