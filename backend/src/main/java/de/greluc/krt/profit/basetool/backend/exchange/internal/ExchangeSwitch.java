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

import com.google.errorprone.annotations.Immutable;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeNotices;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One of the switches on the settings row (REQ-XCH-003, REQ-BXCH-006, REQ-OXCH-009) and everything
 * that differs between them: the mirror document member the gateway reads it from, the settings
 * columns and admin endpoint of an area switch, how its state is read from the row, and how a flip
 * is audited, announced and counted. A new switch is one constant here, its columns in a migration
 * and its fields on {@link ExchangeSettings}.
 */
@RequiredArgsConstructor
public enum ExchangeSwitch {

  /** The global switch, guarded by the row's version and written with the row. */
  GLOBAL(
      "enabled",
      null,
      settings -> new State(settings.isEnabled(), settings.getVersion(), settings.getUpdatedAt()),
      AuditEventType.EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.SWITCH_ON,
      ExchangeRegistryAction.SWITCH_OFF,
      new ExchangeSwitchNotice(
          NotificationEventType.EXCHANGE_SWITCHED_ON,
          NotificationEventType.EXCHANGE_SWITCHED_OFF,
          "EXCHANGE_SWITCH",
          null,
          NotificationType.EXCHANGE_SWITCHED_OFF,
          null,
          true)),

  /** The bank switch, guarded by its own {@code bankVersion}. */
  BANK(
      "bankEnabled",
      new Area("bank", "bank", "bank", "every bank route answers 503 BANK_EXCHANGE_DISABLED"),
      settings ->
          new State(
              settings.isBankEnabled(), settings.getBankVersion(), settings.getBankUpdatedAt()),
      AuditEventType.BANK_EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.BANK_SWITCH_ON,
      ExchangeRegistryAction.BANK_SWITCH_OFF,
      new ExchangeSwitchNotice(
          NotificationEventType.BANK_EXCHANGE_SWITCHED_ON,
          NotificationEventType.BANK_EXCHANGE_SWITCHED_OFF,
          "EXCHANGE_BANK_SWITCH",
          NotificationType.BANK_EXCHANGE_SWITCHED_ON,
          NotificationType.BANK_EXCHANGE_SWITCHED_OFF,
          null,
          false)),

  /** The job-order family switch, guarded by its own {@code jobOrdersVersion}. */
  JOB_ORDERS(
      "jobOrdersEnabled",
      new Area(
          "job-orders",
          "job_orders",
          "job-order",
          "every job-order route answers 503 JOB_ORDER_EXCHANGE_DISABLED"),
      settings ->
          new State(
              settings.isJobOrdersEnabled(),
              settings.getJobOrdersVersion(),
              settings.getJobOrdersUpdatedAt()),
      AuditEventType.JOB_ORDER_EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.JOB_ORDER_SWITCH_ON,
      ExchangeRegistryAction.JOB_ORDER_SWITCH_OFF,
      new ExchangeSwitchNotice(
          NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
          NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_OFF,
          "EXCHANGE_JOB_ORDER_SWITCH",
          NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
          NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_OFF,
          ExchangeNotices.FAMILY_SWITCH,
          false)),

  /** The job-order writes switch, guarded by its own {@code jobOrderWritesVersion}. */
  JOB_ORDER_WRITES(
      "jobOrderWritesEnabled",
      new Area(
          "job-order-writes",
          "job_order_writes",
          "job-order writes",
          "every job-order write answers 503 JOB_ORDER_WRITES_DISABLED and the reads go on"),
      settings ->
          new State(
              settings.isJobOrderWritesEnabled(),
              settings.getJobOrderWritesVersion(),
              settings.getJobOrderWritesUpdatedAt()),
      AuditEventType.JOB_ORDER_EXCHANGE_SWITCH_CHANGED,
      ExchangeRegistryAction.JOB_ORDER_WRITES_SWITCH_ON,
      ExchangeRegistryAction.JOB_ORDER_WRITES_SWITCH_OFF,
      new ExchangeSwitchNotice(
          NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
          NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_OFF,
          "EXCHANGE_JOB_ORDER_WRITES_SWITCH",
          NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
          NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_OFF,
          ExchangeNotices.WRITES_SWITCH,
          false));

  /** The boolean member of the registry mirror document that carries the switch. */
  @Getter @NotNull private final String mirrorField;

  /** The settings columns and the admin endpoint of a switch beside the global one. */
  @Nullable private final Area area;

  /** Reads the switch's state from the settings row. */
  @NotNull private final StateRead state;

  /** The audit event every flip writes. */
  @Getter @NotNull private final AuditEventType auditType;

  /** The metric action of switching on. */
  @NotNull private final ExchangeRegistryAction onAction;

  /** The metric action of switching off. */
  @NotNull private final ExchangeRegistryAction offAction;

  /** The notice every flip publishes. */
  @NotNull private final ExchangeSwitchNotice notice;

  /**
   * The settings columns and the admin endpoint of an area switch.
   *
   * @param segment the last path segment of its admin endpoint under {@code
   *     /api/v1/connected-apps/admin/settings/}
   * @param column the stem of its {@code _enabled}, {@code _version} and {@code _updated_at}
   *     columns on {@code exchange_settings}
   * @param label how the admin API document names the switch
   * @param offAnswer what the gateway answers while the switch is off, as the admin API document
   *     states it
   */
  @Immutable
  public record Area(
      @NotNull String segment,
      @NotNull String column,
      @NotNull String label,
      @NotNull String offAnswer) {

    /**
     * Builds the statement that writes the switch alone: its state, its own version counter and its
     * change time, leaving the row's version and {@code updated_at} and every other switch alone.
     *
     * @return the native {@code UPDATE} with the parameters {@code enabled}, {@code at} and {@code
     *     id}
     */
    @NotNull
    String writeStatement() {
      return ("UPDATE exchange_settings SET %1$s_enabled = :enabled,"
              + " %1$s_version = %1$s_version + 1, %1$s_updated_at = :at WHERE id = :id")
          .formatted(column);
    }
  }

  /**
   * A switch's state on the settings row.
   *
   * @param enabled whether the switch is on
   * @param version its optimistic-lock counter, the version a caller echoes
   * @param updatedAt when it last changed: the row's own change time for the global switch, the
   *     switch's own for every other; {@code null} before the first change
   */
  @Immutable
  public record State(boolean enabled, long version, @Nullable Instant updatedAt) {}

  /** Reads a switch's state from the settings row. */
  @Immutable
  @FunctionalInterface
  interface StateRead {

    /**
     * Reads the state.
     *
     * @param settings the settings row
     * @return the switch's state
     */
    @NotNull
    State of(@NotNull ExchangeSettings settings);
  }

  /**
   * Returns the area switch whose admin endpoint ends in a path segment.
   *
   * @param segment the last path segment
   * @return the switch, or empty when no area switch has that segment
   */
  @NotNull
  public static Optional<ExchangeSwitch> ofSegment(@NotNull String segment) {
    return Arrays.stream(values())
        .filter(target -> target.area != null && target.area.segment().equals(segment))
        .findFirst();
  }

  /**
   * Returns the settings columns and the admin endpoint of the switch.
   *
   * @return them for an area switch, empty for the global switch
   */
  @NotNull
  public Optional<Area> area() {
    return Optional.ofNullable(area);
  }

  /**
   * Reads the switch's state from the settings row.
   *
   * @param settings the settings row
   * @return its state, counter and change time
   */
  @NotNull
  public State stateOf(@NotNull ExchangeSettings settings) {
    return state.of(settings);
  }

  /**
   * Builds the audit details of a flip: {@code {enabled}}, and for a switch with a {@link
   * ExchangeNotices#SWITCH_CODE} {@code {switch, enabled}}.
   *
   * @param enabled the new state
   * @return the details
   */
  @NotNull
  AuditDetails auditDetails(boolean enabled) {
    String switchCode = notice.switchCode();
    return switchCode == null
        ? AuditDetails.of("enabled", enabled)
        : AuditDetails.of("switch", switchCode).with("enabled", enabled);
  }

  /**
   * Builds the notice of a flip for the acting admin.
   *
   * @param enabled the new state
   * @param actor the acting admin, or {@code null}
   * @return the notice to publish
   */
  @NotNull
  public NoticeEvent notice(boolean enabled, @Nullable UUID actor) {
    return notice.flipped(enabled, actor);
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
