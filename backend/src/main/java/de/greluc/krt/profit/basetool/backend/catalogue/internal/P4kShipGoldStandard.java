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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kClosedSet;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kShipPlayability;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.util.StringUtils;

/**
 * Pairs a ship's old record with the {@code _GS} record that supersedes it (REQ-P4K-053): a game
 * build can carry a reworked ship as a new record whose class name is the old one plus {@code _GS}
 * while the old record stays in the files. Both are player ships heading no variant group, under
 * the same in-game name.
 */
final class P4kShipGoldStandard {

  /** The class-name suffix of a superseding record. */
  private static final String SUFFIX = "_GS";

  /** Non-instantiable holder of the rule. */
  private P4kShipGoldStandard() {}

  /**
   * Maps each superseded record's GUID to the record that supersedes it.
   *
   * @param ships the catalogue's ships
   * @return the superseding records by the GUID of the record they supersede
   */
  @NotNull
  @Unmodifiable
  static Map<UUID, P4kShipDto> supersededBy(@NotNull List<P4kShipDto> ships) {
    Map<String, List<P4kShipDto>> byClass = new HashMap<>();
    for (P4kShipDto ship : ships) {
      String key = P4kMatchKeys.fold(ship.className());
      if (key != null && !key.isEmpty()) {
        byClass.computeIfAbsent(key, _ -> new ArrayList<>()).add(ship);
      }
    }
    Map<UUID, P4kShipDto> superseded = new HashMap<>();
    byClass.forEach((key, records) -> {
      String oldKey = oldClassName(key);
      P4kShipDto successor = P4kShipIdentityMoves.sole(records);
      P4kShipDto old = oldKey == null ? null : P4kShipIdentityMoves.sole(byClass.get(oldKey));
      UUID oldGuid = old == null ? null : old.parsedGuid();
      if (successor != null
          && old != null
          && oldGuid != null
          && isPlayerBase(successor)
          && isPlayerBase(old)
          && Objects.equals(P4kMatchKeys.fold(old.nameEn()), P4kMatchKeys.fold(successor.nameEn()))
          && StringUtils.hasText(successor.nameEn())) {
        superseded.put(oldGuid, successor);
      }
    });
    return Map.copyOf(superseded);
  }

  /**
   * Returns the folded class name a superseding record's folded class name reworks.
   *
   * @param key a folded class name
   * @return the class name without the suffix, or {@code null} when it carries none
   */
  @Nullable
  private static String oldClassName(@NotNull String key) {
    return key.length() > SUFFIX.length() && key.endsWith(SUFFIX)
        ? key.substring(0, key.length() - SUFFIX.length())
        : null;
  }

  /**
   * Tells whether a record is a player ship heading no variant group.
   *
   * @param record the record
   * @return whether a player owns it and it is no variant
   */
  private static boolean isPlayerBase(@NotNull P4kShipDto record) {
    return !StringUtils.hasText(record.variantOf())
        && P4kClosedSet.parse(P4kShipPlayability.class, record.playability())
            == P4kShipPlayability.PLAYER;
  }
}
