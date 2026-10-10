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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationRule;
import de.greluc.krt.profit.basetool.backend.model.NotificationRuleSelector;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.SelectorKind;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRuleRepository;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The default rules the migrations seed for the notifications of issue #2414: each event has its
 * enabled rule, raising the intended notification type to the intended kinds of recipient, and
 * excluding the actor where the notice would only tell people what they did themselves.
 */
@SpringBootTest
class SeededNotificationRulesIntegrationTest {

  @Autowired private NotificationRuleRepository notificationRuleRepository;

  private static Arguments rule(
      NotificationEventType event,
      NotificationType type,
      boolean excludeActor,
      SelectorKind... kinds) {
    return Arguments.of(event, type, excludeActor, List.of(kinds));
  }

  static Stream<Arguments> seededRules() {
    return Stream.of(
        rule(
            NotificationEventType.MISSION_RESCHEDULED,
            NotificationType.MISSION_RESCHEDULED,
            true,
            SelectorKind.MISSION_PARTICIPANTS),
        rule(
            NotificationEventType.MISSION_CANCELLED,
            NotificationType.MISSION_CANCELLED,
            true,
            SelectorKind.MISSION_PARTICIPANTS),
        rule(
            NotificationEventType.MISSION_DELETED,
            NotificationType.MISSION_DELETED,
            true,
            SelectorKind.EVENT_RECIPIENTS),
        rule(
            NotificationEventType.MISSION_REMINDER_DUE,
            NotificationType.MISSION_REMINDER,
            false,
            SelectorKind.EVENT_RECIPIENT),
        rule(
            NotificationEventType.MISSION_STARTED,
            NotificationType.MISSION_CHECKIN_OPEN,
            false,
            SelectorKind.MISSION_PARTICIPANTS),
        rule(
            NotificationEventType.MISSION_PARTICIPANT_ADDED,
            NotificationType.MISSION_PARTICIPANT_ADDED_BY_OTHER,
            true,
            SelectorKind.EVENT_RECIPIENT),
        rule(
            NotificationEventType.MISSION_PARTICIPANT_REMOVED,
            NotificationType.MISSION_PARTICIPANT_REMOVED_BY_OTHER,
            true,
            SelectorKind.EVENT_RECIPIENT),
        rule(
            NotificationEventType.MISSION_PARTICIPANT_LEFT,
            NotificationType.MISSION_PARTICIPANT_LEFT,
            true,
            SelectorKind.MISSION_LEADERSHIP),
        rule(
            NotificationEventType.MISSION_NEVER_ENDED,
            NotificationType.MISSION_NEVER_ENDED,
            false,
            SelectorKind.MISSION_LEADERSHIP),
        rule(
            NotificationEventType.MISSION_RESPONSIBILITY_ASSIGNED,
            NotificationType.MISSION_RESPONSIBILITY_ASSIGNED,
            true,
            SelectorKind.EVENT_RECIPIENT),
        rule(
            NotificationEventType.OPERATION_PAYOUT_MARKED,
            NotificationType.OPERATION_PAYOUT_PAID_OUT,
            true,
            SelectorKind.EVENT_RECIPIENT),
        rule(
            NotificationEventType.OPERATION_COMPLETED,
            NotificationType.OPERATION_COMPLETED,
            true,
            SelectorKind.ORG_RELATIVE_ROLE,
            SelectorKind.ORG_RELATIVE_ROLE),
        rule(
            NotificationEventType.OPERATION_COMPLETED_UNOWNED,
            NotificationType.OPERATION_COMPLETED,
            true,
            SelectorKind.ROLE));
  }

  @ParameterizedTest(name = "{0} raises {1}")
  @MethodSource("seededRules")
  void everyEventHasItsEnabledDefaultRule(
      NotificationEventType event,
      NotificationType type,
      boolean excludeActor,
      List<SelectorKind> kinds) {
    List<NotificationRule> rules =
        notificationRuleRepository.findEnabledByEventTypeWithSelectors(event);

    assertThat(rules)
        .singleElement()
        .satisfies(
            rule -> {
              assertThat(rule.getNotificationType()).isEqualTo(type);
              assertThat(rule.isExcludeActor()).isEqualTo(excludeActor);
              assertThat(rule.getSelectors())
                  .extracting(NotificationRuleSelector::getKind)
                  .containsExactlyInAnyOrderElementsOf(kinds);
            });
  }
}
