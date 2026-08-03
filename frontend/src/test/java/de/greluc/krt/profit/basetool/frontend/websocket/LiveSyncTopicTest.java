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

    // The near-identical `order`/`orders` stem must NOT collide: a scoped `order:{id}` is the
    // detail
    // room, while the bare `orders` is the global staff-queue room — distinct classes, keyed apart
    // by
    // prefix and the presence of the id segment.
    LiveSyncTopic queue = LiveSyncTopic.parse("orders");
    assertThat(queue).isNotNull();
    assertThat(queue.topicClass()).isEqualTo(LiveSyncTopicClass.ORDERS_QUEUE);
    // The `order` detail room is scoped, so the bare prefix (no id) is rejected.
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
    // `orders` is a global room; a prefixed id violates its scope.
    assertThat(LiveSyncTopic.parse("orders:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalMissionsListTopic_andDistinguishesItFromTheMissionDetailRoom() {
    // #1235: the `mission`/`missions` stem repeats the `order`/`orders` shape and must not collide
    // — a bare `missions` is the global list room, `mission:{id}` the per-mission detail room.
    // A silent collision here would route mission-detail presence frames into the list room.
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

    // `missions` is global (an id violates its scope); `mission` is scoped (the bare prefix does).
    assertThat(LiveSyncTopic.parse("missions:" + id)).isNull();
    assertThat(LiveSyncTopic.parse("mission")).isNull();
  }

  @Test
  void parse_acceptsGlobalRefineryTopic_andRejectsAnIdOnIt() {
    // #1235: the refinery-order queue room is a bare-prefix global room.
    LiveSyncTopic topic = LiveSyncTopic.parse("refinery");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.REFINERY);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("refinery");
    assertThat(LiveSyncTopic.parse("refinery:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalMembersTopic_andRejectsAnIdOnIt() {
    // #1235: the Mitgliederverwaltung roster room ("Rollen") is a bare-prefix global room.
    LiveSyncTopic topic = LiveSyncTopic.parse("members");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.MEMBERS);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("members");
    assertThat(LiveSyncTopic.parse("members:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalOrgStructureTopic_andRejectsAnIdOnIt() {
    // #1235: the hyphenated prefix shared by the admin editor and the Organigramm. The hyphen is
    // load-bearing — LiveSyncTopic matches prefixes exactly, so a rename would silently 404 the
    // room rather than fall back to a neighbouring class.
    LiveSyncTopic topic = LiveSyncTopic.parse("org-structure");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.ORG_STRUCTURE);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("org-structure");
    assertThat(LiveSyncTopic.parse("org-structure:" + UUID.randomUUID())).isNull();
  }

  @Test
  void membersTopic_isTheOnlyNewClassGatedByALocalAdminRoleCheck() {
    // #1235: the roster room mirrors the page's class-level ADMIN gate with a backend-free local
    // check, while the other three new rooms are authenticated-only on purpose — the missions and
    // refinery lists are isAuthenticated(), and org-structure must admit the members who can see
    // the Organigramm. Pinning this stops a later "tighten it up" from silently cutting members
    // off from their own chart.
    assertThat(LiveSyncTopicClass.MEMBERS.requiredAnyRole()).containsExactly("ROLE_ADMIN");
    assertThat(LiveSyncTopicClass.MISSIONS_LIST.requiredAnyRole()).isNull();
    assertThat(LiveSyncTopicClass.REFINERY.requiredAnyRole()).isNull();
    assertThat(LiveSyncTopicClass.ORG_STRUCTURE.requiredAnyRole()).isNull();

    // None of the four probes the backend, and none carries presence (only `mission` does).
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
    // #1307: the shared-Lager room is a bare-prefix global room (like `orders` / `materialboard`).
    LiveSyncTopic topic = LiveSyncTopic.parse("inventory");

    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.INVENTORY_ALL);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("inventory");
  }

  @Test
  void parse_rejectsGlobalInventoryTopicCarryingAnId() {
    // `inventory` is a global room; a prefixed id violates its scope.
    assertThat(LiveSyncTopic.parse("inventory:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_distinguishesTheSharedBankPrefixByScope() {
    UUID id = UUID.randomUUID();

    // `bank:{id}` is the per-account room; the bare `bank` is the staff room — the SAME prefix,
    // split by the presence of the id segment (the scope-aware classForPrefix fix).
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

    // `bank:` with an empty id is a malformed scoped topic, not a fallback to the staff room.
    assertThat(LiveSyncTopic.parse("bank:")).isNull();
  }

  @Test
  void parse_acceptsGlobalOrgUnitBankTopic_andRejectsAnIdOnIt() {
    LiveSyncTopic topic = LiveSyncTopic.parse("orgunit-bank");
    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.ORGUNIT_BANK);
    assertThat(topic.resourceId()).isNull();
    // A global room; a prefixed id violates its scope.
    assertThat(LiveSyncTopic.parse("orgunit-bank:" + UUID.randomUUID())).isNull();
  }

  @Test
  void parse_acceptsGlobalMaterialboardTopic_andRejectsAnIdOnIt() {
    LiveSyncTopic topic = LiveSyncTopic.parse("materialboard");
    assertThat(topic).isNotNull();
    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.MATERIALBOARD);
    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("materialboard");
    // A global room; a prefixed id violates its scope.
    assertThat(LiveSyncTopic.parse("materialboard:" + UUID.randomUUID())).isNull();
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
    // Metric labels must be unique so the bounded topic_class dimension never collides
    // (REQ-OBS-011).
    long distinctLabels =
        java.util.Arrays.stream(classes).map(LiveSyncTopicClass::metricLabel).distinct().count();
    assertThat(distinctLabels).isEqualTo(classes.length);
  }

  @Test
  void orderRoomsCarryDistinctlyNamedMetricLabels() {
    // The per-order detail room and the global orders queue share the order/orders wire stem but
    // must surface as clearly distinct `topic_class` series on the ops dashboard — not `order` vs
    // `orders`, which read as one accidental duplicate. Pin the disambiguated labels.
    assertThat(LiveSyncTopicClass.ORDER.metricLabel()).isEqualTo("order_detail");
    assertThat(LiveSyncTopicClass.ORDERS_QUEUE.metricLabel()).isEqualTo("orders_queue");
  }
}
