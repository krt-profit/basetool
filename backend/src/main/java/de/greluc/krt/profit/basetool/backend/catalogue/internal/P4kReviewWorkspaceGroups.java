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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/** Groups related findings while keeping distinct identity decisions individually inspectable. */
final class P4kReviewWorkspaceGroups {

  private static final Set<String> GENERIC_ITEM_NAMES = Set.of("door control", "door", "corpse");

  private static final Map<String, Integer> PLACE_ORDER =
      Arrays.stream(StarmapPlaceKind.values())
          .collect(
              Collectors.toUnmodifiableMap(StarmapPlaceKind::getTable, StarmapPlaceKind::ordinal));

  private P4kReviewWorkspaceGroups() {}

  static List<Group> from(
      List<P4kReviewCaseSource> cases, @Nullable P4kKind kind, @Nullable P4kReviewReason reason) {
    Map<GroupKey, List<P4kReviewCaseDto>> groups = new LinkedHashMap<>();
    for (P4kReviewCaseSource entry : cases) {
      P4kReviewCaseDto review = entry.review();
      if ((kind == null || review.kind() == kind)
          && (reason == null || review.reason() == reason)) {
        groups.computeIfAbsent(key(entry), _ -> new ArrayList<>()).add(review);
      }
    }
    return groups.entrySet().stream()
        .map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue())))
        .sorted(
            Comparator.comparing(Group::priority)
                .thenComparingInt(Group::placeOrder)
                .thenComparing(
                    Comparator.comparingInt((Group group) -> group.members().size()).reversed())
                .thenComparing(Group::id))
        .toList();
  }

  private static GroupKey key(P4kReviewCaseSource entry) {
    P4kReviewCaseDto review = entry.review();
    String path = normalized(entry.recordPath());
    String name = normalized(review.nameEn());
    String paintFamily = P4kManufacturerRecordPath.paintFamily(entry.recordPath());
    if (review.kind() == P4kKind.MANUFACTURER
        && review.reason() == P4kReviewReason.GUID_CONFLICT
        && paintFamily != null
        && review.rowId() == null
        && !name.isEmpty()) {
      return new GroupKey(review.kind(), review.reason(), "paint:" + name, paintFamily, true);
    }
    if (review.kind() == P4kKind.ITEM && !path.isEmpty()) {
      String family = parent(path);
      if (review.reason() == P4kReviewReason.GUID_CONFLICT
          && review.rowId() == null
          && GENERIC_ITEM_NAMES.contains(name)) {
        return new GroupKey(review.kind(), review.reason(), name + ":" + family, family, true);
      }
      if (review.reason() == P4kReviewReason.SEEDED_NOT_CREATABLE
          && review.rowId() != null
          && !name.isEmpty()) {
        return new GroupKey(review.kind(), review.reason(), name + ":" + family, family, true);
      }
    }
    if (review.reason() == P4kReviewReason.GUID_CONFLICT
        && review.rowId() != null
        && review.otherRowId() != null) {
      UUID first = review.rowId();
      UUID second = review.otherRowId();
      String pair = first.compareTo(second) <= 0 ? first + ":" + second : second + ":" + first;
      return new GroupKey(review.kind(), review.reason(), pair, null, false);
    }
    return new GroupKey(review.kind(), review.reason(), review.id().toString(), null, false);
  }

  private static String parent(String path) {
    return path.substring(0, Math.max(0, path.lastIndexOf('/')));
  }

  private static String normalized(@Nullable String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
  }

  /** A group identity scoped to its kind and reason. */
  private record GroupKey(
      P4kKind kind,
      P4kReviewReason reason,
      String key,
      @Nullable String contextPath,
      boolean batchable) {}

  /**
   * A group and its current members.
   *
   * @param key the identity of the group
   * @param members the open cases in case order
   */
  record Group(GroupKey key, List<P4kReviewCaseDto> members) {

    UUID id() {
      return UUID.nameUUIDFromBytes(
          (key.kind().name() + ":" + key.reason().name() + ":" + key.key())
              .getBytes(StandardCharsets.UTF_8));
    }

    P4kReviewGroupDto summary() {
      return summary(Map.of());
    }

    P4kReviewGroupDto summary(Map<UUID, P4kReviewReadiness> readinessByCase) {
      EnumSet<P4kReviewReadiness> readiness = EnumSet.noneOf(P4kReviewReadiness.class);
      for (P4kReviewCaseDto member : members) {
        readiness.add(readinessByCase.getOrDefault(member.id(), P4kReviewReadiness.CHECK_REQUIRED));
      }
      P4kReviewReadiness groupReadiness =
          readiness.size() == 1 || readiness.contains(P4kReviewReadiness.ACTIONABLE)
              ? readiness.iterator().next()
              : P4kReviewReadiness.CHECK_REQUIRED;
      P4kReviewCaseDto first = members.getFirst();
      String name =
          Objects.requireNonNullElse(
              first.nameEn(), Objects.requireNonNullElse(first.rowName(), first.guid().toString()));
      return new P4kReviewGroupDto(
          id(),
          first.kind(),
          first.reason(),
          name,
          key.contextPath(),
          members.size(),
          first,
          groupReadiness,
          key.batchable() && members.size() > 1);
    }

    private Priority priority() {
      if (key.reason() == P4kReviewReason.NAME_CONFLICT
          || (key.kind() == P4kKind.SHIP && members.getFirst().rowId() != null)) {
        return Priority.IDENTITY;
      }
      if (key.kind() == P4kKind.STARMAP_OBJECT) {
        return Priority.PLACE;
      }
      if (key.kind() == P4kKind.SHOP) {
        return Priority.SHOP;
      }
      return Priority.REVIEW;
    }

    private int placeOrder() {
      return PLACE_ORDER.getOrDefault(
          normalized(members.getFirst().className()), PLACE_ORDER.size());
    }
  }

  /** Review order, with the place hierarchy before dependent shops. */
  private enum Priority {
    IDENTITY,
    REVIEW,
    PLACE,
    SHOP
  }
}
