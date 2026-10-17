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

package de.greluc.krt.profit.basetool.backend.livesync.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** How a backend-raised room is named and how its sections are clipped (ADR-0274). */
class LiveSyncTopicTest {

  private static final UUID ID = UUID.fromString("8f14e45f-ceea-467a-9c5b-5f1f52a3a1c2");

  @Test
  @DisplayName("a per-resource room carries its prefix, its id and the id in the canonical form")
  void aPerResourceRoomIsNamedByPrefixAndId() {
    LiveSyncTopic topic = LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_UNIT, ID);

    assertThat(topic.topicClass()).isEqualTo(LiveSyncTopicClass.HANGAR_UNIT);
    assertThat(topic.resourceId()).isEqualTo(ID);
    assertThat(topic.canonical()).isEqualTo("hangar-unit:" + ID);
  }

  @Test
  @DisplayName("a global room is named by its prefix alone and carries no id")
  void aGlobalRoomIsNamedByItsPrefix() {
    LiveSyncTopic topic = LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL);

    assertThat(topic.resourceId()).isNull();
    assertThat(topic.canonical()).isEqualTo("inventory");
  }

  @Test
  @DisplayName("the two factories refuse a class of the wrong arity")
  void theFactoriesRefuseTheWrongArity() {
    assertThatThrownBy(() -> LiveSyncTopic.global(LiveSyncTopicClass.HANGAR_UNIT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LiveSyncTopic.of(LiveSyncTopicClass.INVENTORY_ALL, ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(LiveSyncTopicClass.class)
  @DisplayName("every class admits at least one section and nothing outside its whitelist")
  void everyClassClipsToItsWhitelist(LiveSyncTopicClass topicClass) {
    assertThat(topicClass.allowedSections()).isNotEmpty();
    assertThat(topicClass.clipSections(List.of("definitely-not-a-section"))).isEmpty();
    assertThat(topicClass.clipSections(List.copyOf(topicClass.allowedSections())))
        .containsExactlyInAnyOrderElementsOf(topicClass.allowedSections());
  }

  @Test
  @DisplayName("clipping drops nulls and duplicates and keeps wire order")
  void clippingDropsNullsAndDuplicates() {
    assertThat(
            LiveSyncTopicClass.MATERIALBOARD.clipSections(
                Arrays.asList("board", null, "requests", "board")))
        .containsExactly("board");
  }

  @Test
  @DisplayName("no sections at all clips to nothing rather than raising")
  void noSectionsClipsToNothing() {
    assertThat(LiveSyncTopicClass.INVENTORY_ALL.clipSections(null)).isEmpty();
    assertThat(LiveSyncTopicClass.INVENTORY_ALL.clipSections(List.of())).isEmpty();
  }
}
