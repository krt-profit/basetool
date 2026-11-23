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
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeNotices;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One of the switches on the settings row (REQ-XCH-003, REQ-BXCH-006, REQ-OXCH-009): how its state,
 * its own optimistic-lock counter and its change time are read, and how a flip is audited,
 * announced and counted.
 */
@RequiredArgsConstructor
enum ExchangeSwitch {

  /** The global switch, guarded by the row's version. */
  GLOBAL(
      AuditEventType.EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.SWITCH_ON,
      ExchangeRegistryAction.SWITCH_OFF),

  /** The bank switch, guarded by its own {@code bankVersion}. */
  BANK(
      AuditEventType.BANK_EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.BANK_SWITCH_ON,
      ExchangeRegistryAction.BANK_SWITCH_OFF),

  /** The job-order family switch, guarded by its own {@code jobOrdersVersion}. */
  JOB_ORDERS(
      AuditEventType.JOB_ORDER_EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.JOB_ORDER_SWITCH_ON,
      ExchangeRegistryAction.JOB_ORDER_SWITCH_OFF),

  /** The job-order writes switch, guarded by its own {@code jobOrderWritesVersion}. */
  JOB_ORDER_WRITES(
      AuditEventType.JOB_ORDER_EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.JOB_ORDER_WRITES_SWITCH_ON,
      ExchangeRegistryAction.JOB_ORDER_WRITES_SWITCH_OFF);

  /** The audit event every flip writes. */
  @Getter @NotNull private final AuditEventType auditType;

  /** The metric action of switching on. */
  @NotNull private final ExchangeRegistryAction onAction;

  /** The metric action of switching off. */
  @NotNull private final ExchangeRegistryAction offAction;

  /**
   * Tells whether the switch is on.
   *
   * @param settings the settings row
   * @return the switch's state
   */
  boolean isOn(@NotNull ExchangeSettings settings) {
    return switch (this) {
      case GLOBAL -> settings.isEnabled();
      case BANK -> settings.isBankEnabled();
      case JOB_ORDERS -> settings.isJobOrdersEnabled();
      case JOB_ORDER_WRITES -> settings.isJobOrderWritesEnabled();
    };
  }

  /**
   * Reads the switch's optimistic-lock counter, the version a caller echoes.
   *
   * @param settings the settings row
   * @return the counter
   */
  @NotNull
  Long versionOf(@NotNull ExchangeSettings settings) {
    return switch (this) {
      case GLOBAL -> settings.getVersion();
      case BANK -> settings.getBankVersion();
      case JOB_ORDERS -> settings.getJobOrdersVersion();
      case JOB_ORDER_WRITES -> settings.getJobOrderWritesVersion();
    };
  }

  /**
   * Reads when the switch last changed: the row's own change time for the global switch, the
   * switch's own change time for every other.
   *
   * @param settings the settings row
   * @return the change time, or {@code null} before the first change
   */
  @Nullable
  Instant updatedAtOf(@NotNull ExchangeSettings settings) {
    return switch (this) {
      case GLOBAL -> settings.getUpdatedAt();
      case BANK -> settings.getBankUpdatedAt();
      case JOB_ORDERS -> settings.getJobOrdersUpdatedAt();
      case JOB_ORDER_WRITES -> settings.getJobOrderWritesUpdatedAt();
    };
  }

  /**
   * Builds the audit details of a flip: {@code {enabled}}, and for a job-order switch {@code
   * {switch, enabled}} with {@code switch} {@code family} or {@code writes}.
   *
   * @param enabled the new state
   * @return the details
   */
  @NotNull
  AuditDetails auditDetails(boolean enabled) {
    return switch (this) {
      case GLOBAL, BANK -> AuditDetails.of("enabled", enabled);
      case JOB_ORDERS ->
          AuditDetails.of("switch", ExchangeNotices.FAMILY_SWITCH).with("enabled", enabled);
      case JOB_ORDER_WRITES ->
          AuditDetails.of("switch", ExchangeNotices.WRITES_SWITCH).with("enabled", enabled);
    };
  }

  /**
   * Builds the notice of a flip for the acting admin.
   *
   * @param enabled the new state
   * @param actor the acting admin, or {@code null}
   * @return the notice to publish
   */
  @NotNull
  NoticeEvent notice(boolean enabled, @Nullable UUID actor) {
    return switch (this) {
      case GLOBAL ->
          enabled ? ExchangeNotices.switchedOn(actor) : ExchangeNotices.switchedOff(actor);
      case BANK ->
          enabled ? ExchangeNotices.bankSwitchedOn(actor) : ExchangeNotices.bankSwitchedOff(actor);
      case JOB_ORDERS ->
          enabled
              ? ExchangeNotices.jobOrdersSwitchedOn(actor)
              : ExchangeNotices.jobOrdersSwitchedOff(actor);
      case JOB_ORDER_WRITES ->
          enabled
              ? ExchangeNotices.jobOrderWritesSwitchedOn(actor)
              : ExchangeNotices.jobOrderWritesSwitchedOff(actor);
    };
  }

  /**
   * Names the metric action of a flip.
   *
   * @param enabled the new state
   * @return the action
   */
  @NotNull
  ExchangeRegistryAction action(boolean enabled) {
    return enabled ? onAction : offAction;
  }
}
