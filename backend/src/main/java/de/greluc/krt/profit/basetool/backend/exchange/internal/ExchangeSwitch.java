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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeNotices;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One of the two switches on the settings row (REQ-XCH-003, REQ-BXCH-006): how its state and its
 * own optimistic-lock counter are read, and how a flip is audited, announced and counted.
 */
@RequiredArgsConstructor
enum ExchangeSwitch {

  /** The global switch, guarded by the row's version. */
  GLOBAL(
      ExchangeSettings::isEnabled,
      ExchangeSettings::getVersion,
      AuditEventType.EXCHANGE_SWITCH_CHANGED,
      ExchangeNotices::switchedOn,
      ExchangeNotices::switchedOff,
      ExchangeRegistryAction.SWITCH_ON,
      ExchangeRegistryAction.SWITCH_OFF),

  /** The bank switch, guarded by its own {@code bankVersion}. */
  BANK(
      ExchangeSettings::isBankEnabled,
      ExchangeSettings::getBankVersion,
      AuditEventType.BANK_EXCHANGE_SWITCH_CHANGED,
      ExchangeNotices::bankSwitchedOn,
      ExchangeNotices::bankSwitchedOff,
      ExchangeRegistryAction.BANK_SWITCH_ON,
      ExchangeRegistryAction.BANK_SWITCH_OFF);

  /** Reads whether the switch is on. */
  @NotNull private final Predicate<ExchangeSettings> state;

  /** Reads the switch's optimistic-lock counter. */
  @NotNull private final Function<ExchangeSettings, Long> version;

  /** The audit event every flip writes, with {@code {enabled}}. */
  @Getter @NotNull private final AuditEventType auditType;

  /** Builds the notice of switching on for the acting admin. */
  @NotNull private final Function<UUID, NoticeEvent> switchedOn;

  /** Builds the notice of switching off for the acting admin. */
  @NotNull private final Function<UUID, NoticeEvent> switchedOff;

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
    return state.test(settings);
  }

  /**
   * Reads the switch's optimistic-lock counter, the version a caller echoes.
   *
   * @param settings the settings row
   * @return the counter
   */
  @NotNull
  Long versionOf(@NotNull ExchangeSettings settings) {
    return version.apply(settings);
  }

  /**
   * Builds the notice of a flip.
   *
   * @param enabled the new state
   * @param actor the acting admin, or {@code null}
   * @return the notice to publish
   */
  @NotNull
  NoticeEvent notice(boolean enabled, @Nullable UUID actor) {
    return (enabled ? switchedOn : switchedOff).apply(actor);
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
