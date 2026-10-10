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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Switches off a registry mirror document left in Redis by an earlier run while mirroring is off
 * (REQ-XCH-003), so the gateway refuses every exchange request with {@code 503 EXCHANGE_DISABLED}
 * instead of admitting clients on a registry nothing keeps in line.
 *
 * <p>Runs once when the application is ready, and only while the disabled mirror is wired. A
 * document whose switch is on is rewritten with the same clients, the switch off and a new
 * revision; a missing, unreadable or already switched-off document is left alone. It needs only the
 * backend ACL user's {@code GET} and {@code SET} on {@code exchange:*}.
 */
@Slf4j
@Component
public class ExchangeRegistryMirrorClosure {

  /** The mirror the application wired; the closure acts only when it is the disabled one. */
  private final ExchangeRegistryMirror mirror;

  /** Reads and writes the document under the configured key, whatever mirror is wired. */
  private final ExchangeRegistryMirror document;

  /** Draws the new document's revision. */
  private final ExchangeSettingsRepository settingsRepository;

  /** The key and the closure's switch. */
  private final ExchangeMirrorProperties properties;

  /** Counts the outcome as {@code basetool_exchange_mirror_writes_total{phase="switched_off"}}. */
  private final MeterRegistry meterRegistry;

  /** A read-write transaction for drawing the revision. */
  private final TransactionTemplate transaction;

  /**
   * Creates the closure.
   *
   * @param mirror the wired mirror, active or disabled
   * @param redisTemplate the string template the document is read and written with
   * @param properties the mirror key and whether the closure runs
   * @param settingsRepository draws the revision from the database sequence
   * @param meterRegistry the registry the outcome is counted in
   * @param transactionManager the manager the revision is drawn in
   */
  public ExchangeRegistryMirrorClosure(
      @NotNull ExchangeRegistryMirror mirror,
      @NotNull StringRedisTemplate redisTemplate,
      @NotNull ExchangeMirrorProperties properties,
      @NotNull ExchangeSettingsRepository settingsRepository,
      @NotNull MeterRegistry meterRegistry,
      @NotNull PlatformTransactionManager transactionManager) {
    this.mirror = mirror;
    this.document =
        new RedisExchangeRegistryMirror(redisTemplate, properties.key(), Clock.systemUTC());
    this.settingsRepository = settingsRepository;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * Switches off a document left behind once the application is ready; a failure is counted and
   * logged, never thrown.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void closeLeftDocument() {
    if (mirror.isActive() || !properties.closeWhenOff()) {
      return;
    }
    try {
      if (close()) {
        count(MetricNames.OUTCOME_WRITTEN);
        log.warn(
            "Exchange registry mirroring is off; the document left under {} was switched off",
            properties.key());
      } else {
        count(MetricNames.OUTCOME_UNCHANGED);
      }
    } catch (RuntimeException e) {
      count(MetricNames.OUTCOME_FAILED);
      log.warn(
          "Exchange registry mirroring is off and the document under {} could not be checked: {}",
          properties.key(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * Rewrites a document whose switch is on with the switch off.
   *
   * @return {@code true} when a document was switched off
   * @throws RuntimeException when Redis or the revision sequence could not be reached
   */
  private boolean close() {
    Optional<ExchangeRegistrySnapshot> left = document.read();
    if (left.isEmpty() || !left.get().enabled()) {
      return false;
    }
    Long revision = transaction.execute(status -> settingsRepository.nextRevision());
    if (revision == null) {
      throw new IllegalStateException("No mirror revision was drawn");
    }
    document.write(new ExchangeRegistrySnapshot(false, left.get().clients()), revision);
    return true;
  }

  /**
   * Counts one outcome.
   *
   * @param outcome {@code written}, {@code unchanged} or {@code failed}
   */
  private void count(@NotNull String outcome) {
    meterRegistry
        .counter(
            MetricNames.EXCHANGE_MIRROR_WRITES,
            MetricNames.TAG_PHASE,
            ExchangeMirrorPhase.SWITCHED_OFF.getTag(),
            MetricNames.TAG_OUTCOME,
            outcome)
        .increment();
  }
}
