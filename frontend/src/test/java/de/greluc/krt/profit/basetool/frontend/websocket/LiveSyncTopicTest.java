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

package de.greluc.krt.profit.basetool.frontend.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LiveSyncTopic#parse(String)} and the {@link LiveSyncTopicClass} registry: a
 * scoped class round-trips a UUID into a normalised canonical string, an unknown prefix or a
 * scope/id mismatch is rejected, and every registered class is internally consistent.
 */
class LiveSyncTopicTest {

  @Test
  void parse_acceptsScopedMissionTopic_andNormalisesTheCanonical() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic topic = LiveSyncTopic.parse("mission:" + id);

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.MISSION);
    assertThat(topic.resourceId()).isEqualTo(id);
    assertThat(topic.canonical()).isEqualTo("mission:" + id);
  }

  @Test
  void parse_acceptsScopedOperationTopic() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic topic = LiveSyncTopic.parse("operation:" + id);

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.OPERATION);
    assertThat(topic.resourceId()).isEqualTo(id);
    assertThat(topic.canonical()).isEqualTo("operation:" + id);
  }

  @Test
  void parse_acceptsScopedOrderTopic_andDistinguishesItFromTheOrdersQueue() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic order = LiveSyncTopic.parse("order:" + id);
    assertThat(order).isNotNull();
    assertThat(order.topicClass()).isEqualTo(LiveSyncTopicClass.ORDER);
    assertThat(order.resourceId()).isEqualTo(id);
    assertThat(order.canonical()).isEqualTo("order:" + id);

    LiveSyncTopic queue = LiveSyncTopic.parse("orders");
    assertThat(queue).isNotNull();
    assertThat(queue.topicClass()).isEqualTo(LiveSyncTopicClass.ORDERS_QUEUE);
    assertThat(LiveSyncTopic.parse("order")).isNull();
  }

  @Test
  void parse_acceptsGlobalOrdersQueueTopic() {
    LiveSyncTopic topic = LiveSyncTopic.parse("orders");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.ORDERS_QUEUE);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("orders");
  }

  @Test
  void parse_rejectsGlobalOrdersQueueTopicCarryingAnId() {
    assertThat(LiveSyncTopic.parse("orders:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalMissionsListTopic_andDistinguishesItFromTheMissionDetailRoom() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic list = LiveSyncTopic.parse("missions");
    assertThat(list).isNotNull();
    assertThat(list.topicClass()).isEqualTo(LiveSyncTopicClass.MISSIONS_LIST);
    assertThat(list.resourceId()).isNull();
    assertThat(list.canonical()).isEqualTo("missions");

    LiveSyncTopic detail = LiveSyncTopic.parse("mission:" + id);
    assertThat(detail).isNotNull();
    assertThat(detail.topicClass()).isEqualTo(LiveSyncTopicClass.MISSION);
    assertThat(detail.resourceId()).isEqualTo(id);

    assertThat(LiveSyncTopic.parse("missions:" + id)).isNull();
    assertThat(LiveSyncTopic.parse("mission")).isNull();
  }

  @Test
  void parse_acceptsGlobalRefineryTopic_andRejectsAnIdOnIt() {
    LiveSyncTopic topic = LiveSyncTopic.parse("refinery");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.REFINERY);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("refinery");
    assertThat(LiveSyncTopic.parse("refinery:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalMembersTopic_andRejectsAnIdOnIt() {
    LiveSyncTopic topic = LiveSyncTopic.parse("members");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.MEMBERS);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("members");
    assertThat(LiveSyncTopic.parse("members:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalOrgStructureTopic_andRejectsAnIdOnIt() {
    LiveSyncTopic topic = LiveSyncTopic.parse("org-structure");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.ORG_STRUCTURE);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("org-structure");
    assertThat(LiveSyncTopic.parse("org-structure:" + UUID.randomUUID())).isNull();
  }

  @Test
  void membersTopic_isTheOnlyNewClassGatedByALocalAdminRoleCheck() {
    assertThat(LiveSyncTopicClass.MEMBERS.requiredAnyRole()).containsExactly("ROLE_ADMIN");
    assertThat(LiveSyncTopicClass.MISSIONS_LIST.requiredAnyRole()).isNull();
    assertThat(LiveSyncTopicClass.REFINERY.requiredAnyRole()).isNull();
    assertThat(LiveSyncTopicClass.ORG_STRUCTURE.requiredAnyRole()).isNull();

    for (LiveSyncTopicClass added :
        List.of(
            LiveSyncTopicClass.MISSIONS_LIST,
            LiveSyncTopicClass.REFINERY,
            LiveSyncTopicClass.MEMBERS,
            LiveSyncTopicClass.ORG_STRUCTURE)) {
      assertThat(added.authProbePath()).as("%s auth probe", added).isNull();
      assertThat(added.capabilityField()).as("%s capability", added).isNull();
      assertThat(added.presenceEnabled()).as("%s presence", added).isFalse();
      assertThat(added.scoped()).as("%s scope", added).isFalse();
    }
  }

  @Test
  void parse_acceptsGlobalInventoryTopic() {
    LiveSyncTopic topic = LiveSyncTopic.parse("inventory");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.INVENTORY_ALL);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("inventory");
  }

  @Test
  void parse_rejectsGlobalInventoryTopicCarryingAnId() {
    assertThat(LiveSyncTopic.parse("inventory:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_distinguishesTheSharedBankPrefixByScope() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic account = LiveSyncTopic.parse("bank:" + id);
    assertThat(account).isNotNull();
    assertThat(account.topicClass()).isEqualTo(LiveSyncTopicClass.BANK_ACCOUNT);
    assertThat(account.resourceId()).isEqualTo(id);
    assertThat(account.canonical()).isEqualTo("bank:" + id);

    LiveSyncTopic staff = LiveSyncTopic.parse("bank");
    assertThat(staff).isNotNull();
    assertThat(staff.topicClass()).isEqualTo(LiveSyncTopicClass.BANK_STAFF);
    assertThat(staff.resourceId()).isNull();
    assertThat(staff.canonical()).isEqualTo("bank");

    assertThat(LiveSyncTopic.parse("bank:")).isNull();
  }

  @Test
  void parse_acceptsGlobalOrgUnitBankTopic_andRejectsAnIdOnIt() {
    LiveSyncTopic topic = LiveSyncTopic.parse("orgunit-bank");
    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.ORGUNIT_BANK);
    assertThat(topic.resourceId()).isNull();
    assertThat(LiveSyncTopic.parse("orgunit-bank:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalMaterialboardTopic_andRejectsAnIdOnIt() {
    LiveSyncTopic topic = LiveSyncTopic.parse("materialboard");
    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.MATERIALBOARD);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("materialboard");
    assertThat(LiveSyncTopic.parse("materialboard:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_keepsTheScopedRefineryOrderRoomAndTheGlobalRefineryQueueApart() {
    UUID id = UUID.randomUUID();

    LiveSyncTopic detail = LiveSyncTopic.parse("refinery-order:" + id);
    assertThat(detail).isNotNull();
    assertThat(detail.topicClass()).isEqualTo(LiveSyncTopicClass.REFINERY_ORDER);
    assertThat(detail.resourceId()).isEqualTo(id);
    assertThat(detail.canonical()).isEqualTo("refinery-order:" + id);

    LiveSyncTopic queue = LiveSyncTopic.parse("refinery");
    assertThat(queue).isNotNull();
    assertThat(queue.topicClass()).isEqualTo(LiveSyncTopicClass.REFINERY);
    assertThat(queue.resourceId()).isNull();

    assertThat(LiveSyncTopic.parse("refinery-order")).isNull();
    assertThat(LiveSyncTopic.parse("refinery:" + id)).isNull();

    assertThat(LiveSyncTopicClass.REFINERY_ORDER.metricLabel()).isEqualTo("refinery_order");
    assertThat(LiveSyncTopicClass.REFINERY.metricLabel()).isEqualTo("refinery_queue");
  }

  @Test
  void everyScopedClassExposesAnAuthProbePathWithAnIdPlaceholder() {
    for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
      if (topicClass.scoped()) {
        assertThat(topicClass.authProbePath())
            .as("scoped class %s has a subscribe-auth probe path", topicClass)
            .isNotBlank();
        assertThat(topicClass.authProbePath()).contains("{id}");
      }
    }
  }

  @Test
  void parse_rejectsUnknownPrefix() {
    assertThat(LiveSyncTopic.parse("bogus:" + UUID.randomUUID())).isNull();
    assertThat(LiveSyncTopic.parse("bogus")).isNull();
  }

  @Test
  void parse_rejectsScopedClassWithoutAValidUuid() {
    assertThat(LiveSyncTopic.parse("mission")).isNull();
    assertThat(LiveSyncTopic.parse("mission:")).isNull();
    assertThat(LiveSyncTopic.parse("mission:not-a-uuid")).isNull();
  }

  @Test
  void parse_rejectsNullEmptyAndOverlongInput() {
    assertThat(LiveSyncTopic.parse(null)).isNull();
    assertThat(LiveSyncTopic.parse("")).isNull();
    assertThat(LiveSyncTopic.parse("mission:" + "x".repeat(200))).isNull();
  }

  @Test
  void everyRegisteredClassHasANonEmptyWhitelistAndDistinctMetricLabel() {
    LiveSyncTopicClass[] classes = LiveSyncTopicClass.values();
    for (LiveSyncTopicClass topicClass : classes) {
      assertThat(topicClass.allowedSections()).as("whitelist of %s", topicClass).isNotEmpty();
      assertThat(topicClass.prefix()).as("prefix of %s", topicClass).isNotBlank();
      assertThat(topicClass.metricLabel()).as("metric label of %s", topicClass).isNotBlank();
    }
    long distinctLabels =
        java.util.Arrays.stream(classes).map(LiveSyncTopicClass::metricLabel).distinct().count();
    assertThat(distinctLabels).isEqualTo(classes.length);
  }

  @Test
  void orderRoomsCarryDistinctlyNamedMetricLabels() {
    assertThat(LiveSyncTopicClass.ORDER.metricLabel()).isEqualTo("order_detail");
    assertThat(LiveSyncTopicClass.ORDERS_QUEUE.metricLabel()).isEqualTo("orders_queue");
  }
}
