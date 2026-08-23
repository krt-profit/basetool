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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Parsing and canonicalisation of a live-sync topic string (ADR-0143). */
class LiveSyncTopicTest {

  private static final UUID ID = UUID.fromString("8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2");

  @Nested
  @DisplayName("the four colliding wire stems stay apart")
  class CollidingStems {

    @Test
    @DisplayName("'mission' with an id is the detail room, 'missions' without one is the list")
    void missionAndMissionsAreDifferentRooms() {
      assertThat(LiveSyncTopic.parse("mission:" + ID))
          .isNotNull()
          .extracting(LiveSyncTopic::topicClass)
          .isEqualTo(LiveSyncTopicClass.MISSION);
      assertThat(LiveSyncTopic.parse("missions"))
          .isNotNull()
          .extracting(LiveSyncTopic::topicClass)
          .isEqualTo(LiveSyncTopicClass.MISSIONS_LIST);
    }

    @Test
    @DisplayName("'order' with an id is the detail room, 'orders' without one is the queue")
    void orderAndOrdersAreDifferentRooms() {
      assertThat(LiveSyncTopic.parse("order:" + ID))
          .isNotNull()
          .extracting(LiveSyncTopic::topicClass)
          .isEqualTo(LiveSyncTopicClass.ORDER);
      assertThat(LiveSyncTopic.parse("orders"))
          .isNotNull()
          .extracting(LiveSyncTopic::topicClass)
          .isEqualTo(LiveSyncTopicClass.ORDERS_QUEUE);
    }

    @Test
    @DisplayName("'refinery-order' with an id and 'refinery' without one are different rooms")
    void refineryOrderAndRefineryQueueAreDifferentRooms() {
      assertThat(LiveSyncTopic.parse("refinery-order:" + ID))
          .isNotNull()
          .extracting(LiveSyncTopic::topicClass)
          .isEqualTo(LiveSyncTopicClass.REFINERY_ORDER);
      assertThat(LiveSyncTopic.parse("refinery"))
          .isNotNull()
          .extracting(LiveSyncTopic::topicClass)
          .isEqualTo(LiveSyncTopicClass.REFINERY);
    }

    @Test
    @DisplayName("a bare 'bank' is the frontend's staff room and this backend refuses it")
    void bareBankIsRefused() {
      assertThat(LiveSyncTopic.parse("bank:" + ID))
          .isNotNull()
          .extracting(LiveSyncTopic::topicClass)
          .isEqualTo(LiveSyncTopicClass.BANK_ACCOUNT);
      // The staff surface is web-only; admitting the bare prefix would open a room with no reader
      // and hand a bank employee a stream the app has nothing to do with.
      assertThat(LiveSyncTopic.parse("bank")).isNull();
    }
  }

  @Nested
  @DisplayName("arity is part of the match")
  class Arity {

    @Test
    @DisplayName("a per-resource class without an id is refused")
    void perResourceWithoutIdIsRefused() {
      assertThat(LiveSyncTopic.parse("mission")).isNull();
      assertThat(LiveSyncTopic.parse("operation")).isNull();
    }

    @Test
    @DisplayName("a global class with an id is refused")
    void globalWithIdIsRefused() {
      assertThat(LiveSyncTopic.parse("materialboard:" + ID)).isNull();
      assertThat(LiveSyncTopic.parse("inventory:" + ID)).isNull();
    }
  }

  @Nested
  @DisplayName("the id has to be a whole, well-formed UUID")
  class Ids {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "mission:not-a-uuid",
          "mission:1-1-1-1-1",
          "mission:8f14e45f-ceea-467a-9c5b",
          "mission:",
          "mission:8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2-extra"
        })
    @DisplayName("a short, padded or malformed id is refused")
    void malformedIdsAreRefused(String raw) {
      // `1-1-1-1-1` is the one that matters: UUID.fromString accepts it and re-renders it padded,
      // so without the round-trip check two different wire strings would key the same room.
      assertThat(LiveSyncTopic.parse(raw)).isNull();
    }

    @Test
    @DisplayName("an upper-case id canonicalises to lower case, so both open the same room")
    void idIsCanonicalisedToLowerCase() {
      LiveSyncTopic upper =
          LiveSyncTopic.parse("mission:" + ID.toString().toUpperCase(java.util.Locale.ROOT));
      LiveSyncTopic lower = LiveSyncTopic.parse("mission:" + ID);
      assertThat(upper).isNotNull();
      assertThat(lower).isNotNull();
      assertThat(upper.canonical()).isEqualTo(lower.canonical());
      assertThat(upper.canonical()).isEqualTo("mission:" + ID);
    }
  }

  @Nested
  @DisplayName("hostile and empty input")
  class BadInput {

    @Test
    @DisplayName("null, blank and unknown prefixes answer null rather than throwing")
    void unusableInputIsNull() {
      assertThat(LiveSyncTopic.parse(null)).isNull();
      assertThat(LiveSyncTopic.parse("   ")).isNull();
      assertThat(LiveSyncTopic.parse("definitely-not-a-room")).isNull();
    }

    @Test
    @DisplayName("an oversized topic is refused before anything splits on it")
    void oversizedTopicIsRefused() {
      assertThat(LiveSyncTopic.parse("mission:" + "a".repeat(LiveSyncTopic.MAX_LENGTH))).isNull();
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void whitespaceIsTrimmed() {
      assertThat(LiveSyncTopic.parse("  materialboard "))
          .isNotNull()
          .extracting(LiveSyncTopic::canonical)
          .isEqualTo("materialboard");
    }
  }

  @Nested
  @DisplayName("section clipping")
  class Clipping {

    @Test
    @DisplayName("unknown keys are dropped, known ones survive in order and without duplicates")
    void unknownSectionsAreDropped() {
      // A newer peer naming a section this build does not know must not cost the sections it does.
      assertThat(
              LiveSyncTopicClass.MISSION.clipSections(
                  List.of("crew", "not-a-section", "crew", "finance")))
          .containsExactly("crew", "finance");
    }

    @Test
    @DisplayName("a list that clips to nothing is empty, never a wildcard")
    void allUnknownClipsToEmpty() {
      assertThat(LiveSyncTopicClass.INVENTORY_ALL.clipSections(List.of("queue", "crew"))).isEmpty();
      assertThat(LiveSyncTopicClass.INVENTORY_ALL.clipSections(null)).isEmpty();
    }

    @Test
    @DisplayName("the raw list is bounded before it is filtered")
    void oversizedRawListIsBounded() {
      List<String> flood = new java.util.ArrayList<>();
      for (int i = 0; i < LiveSyncTopicClass.MAX_SECTIONS_PER_FRAME * 4; i++) {
        flood.add("filler-" + i);
      }
      flood.add("stock");
      // 'stock' sits past the cap, so it is never examined — the point of the bound is that the
      // filter's cost cannot be driven by the sender.
      assertThat(LiveSyncTopicClass.INVENTORY_ALL.clipSections(flood)).isEmpty();
    }

    @Test
    @DisplayName("the frame cap sits above every class whitelist, so it never clips a real frame")
    void frameCapIsAboveEveryWhitelist() {
      for (LiveSyncTopicClass topicClass : LiveSyncTopicClass.values()) {
        assertThat(topicClass.allowedSections().size())
            .as("%s whitelist fits under the frame cap", topicClass)
            .isLessThanOrEqualTo(LiveSyncTopicClass.MAX_SECTIONS_PER_FRAME);
      }
    }
  }
}
