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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kManufacturerDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The brands behind the catalogue's canonical manufacturer records (REQ-P4K-058). Records sharing a
 * {@code @manufacturer_Name…} key with the same or a blank code are one brand: its primary is the
 * record whose class name is the key's suffix ({@code RSI} for {@code @manufacturer_NameRSI}), else
 * the one with the shortest class name, and the others ({@code RSI_2025}, {@code MISC_ENV}) are its
 * siblings. A record sharing the key with a different code borrows the brand's name ({@code FSKI}
 * named "Aegis Dynamics"); the key's owner is then the brand it borrows from.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class P4kManufacturerFamilies {

  /** The lower-cased prefix of the name key of a manufacturer record. */
  private static final String NAME_KEY_PREFIX = "@manufacturer_name";

  /** The canonical records by GUID. */
  @NotNull private final Map<UUID, P4kManufacturerDto> records;

  /** The primary GUID of every family member, the primary included. */
  @NotNull private final Map<UUID, UUID> primaries;

  /** The GUIDs of every family's members by its primary GUID. */
  @NotNull private final Map<UUID, List<UUID>> members;

  /** The key owner's GUID of every borrowing record. */
  @NotNull private final Map<UUID, UUID> lenders;

  /**
   * Groups the canonical records into brands.
   *
   * @param canonical the catalogue's manufacturers without the auxiliary records
   * @return the families and borrowers among them
   */
  @NotNull
  static P4kManufacturerFamilies of(@NotNull List<P4kManufacturerDto> canonical) {
    Map<UUID, P4kManufacturerDto> records = new HashMap<>();
    Map<String, P4kManufacturerDto> byClass = new HashMap<>();
    Map<String, List<P4kManufacturerDto>> byKey = new HashMap<>();
    for (P4kManufacturerDto record : canonical) {
      UUID guid = record.parsedGuid();
      if (guid == null) {
        continue;
      }
      records.putIfAbsent(guid, record);
      byClass.putIfAbsent(fold(record.className()), record);
      String key = fold(P4kLocalisedText.keyOf(record.name()));
      if (key.startsWith(NAME_KEY_PREFIX)) {
        byKey.computeIfAbsent(key, _ -> new ArrayList<>()).add(record);
      }
    }
    Map<UUID, UUID> primaries = new HashMap<>();
    Map<UUID, List<UUID>> members = new HashMap<>();
    Map<UUID, UUID> lenders = new HashMap<>();
    byKey.forEach((key, shared) -> {
      if (shared.size() < 2) {
        return;
      }
      P4kManufacturerDto owner = byClass.get(key.substring(NAME_KEY_PREFIX.length()));
      P4kManufacturerDto primary =
          owner != null && shared.contains(owner)
              ? owner
              : shared.stream()
                  .min(
                      Comparator.comparingInt(
                              (P4kManufacturerDto r) -> fold(r.className()).length())
                          .thenComparing(r -> fold(r.className())))
                  .orElseThrow();
      UUID primaryGuid = Objects.requireNonNull(primary.parsedGuid());
      String code = fold(primary.code());
      List<UUID> family = new ArrayList<>();
      for (P4kManufacturerDto record : shared) {
        UUID guid = Objects.requireNonNull(record.parsedGuid());
        String own = fold(record.code());
        if (own.isEmpty() || own.equals(code)) {
          primaries.put(guid, primaryGuid);
          family.add(guid);
        } else {
          lenders.put(guid, primaryGuid);
        }
      }
      if (family.size() > 1) {
        members.put(primaryGuid, List.copyOf(family));
      } else {
        primaries.remove(primaryGuid);
      }
    });
    return new P4kManufacturerFamilies(records, primaries, members, lenders);
  }

  /**
   * Returns the primary of the brand a record belongs to.
   *
   * @param guid a record's GUID
   * @return the primary's GUID, the GUID itself for a primary, or {@code null} outside a family
   */
  @Nullable
  UUID primaryOf(@Nullable UUID guid) {
    return guid == null ? null : primaries.get(guid);
  }

  /**
   * Tells whether a record is a sibling of a brand's primary.
   *
   * @param guid a record's GUID
   * @return {@code true} for a family member that is not the primary
   */
  boolean isSibling(@Nullable UUID guid) {
    UUID primary = primaryOf(guid);
    return primary != null && !primary.equals(guid);
  }

  /**
   * Lists the members of a brand.
   *
   * @param primary the primary's GUID
   * @return the members' GUIDs, the primary included, or none for a record outside a family
   */
  @NotNull
  List<UUID> membersOf(@NotNull UUID primary) {
    return members.getOrDefault(primary, List.of());
  }

  /**
   * Returns the brand whose name a record borrows.
   *
   * @param guid a record's GUID
   * @return the key owner's GUID, or {@code null} when the record borrows no name
   */
  @Nullable
  UUID lenderOf(@Nullable UUID guid) {
    return guid == null ? null : lenders.get(guid);
  }

  /**
   * Lists the records borrowing a brand's name.
   *
   * @param lender the key owner's GUID
   * @return the borrowers' GUIDs
   */
  @NotNull
  List<UUID> borrowersOf(@NotNull UUID lender) {
    return lenders.entrySet().stream()
        .filter(entry -> entry.getValue().equals(lender))
        .map(Map.Entry::getKey)
        .toList();
  }

  /**
   * Returns a canonical record.
   *
   * @param guid its GUID
   * @return the record, or {@code null} when the catalogue lists none
   */
  @Nullable
  P4kManufacturerDto record(@Nullable UUID guid) {
    return guid == null ? null : records.get(guid);
  }

  /**
   * Folds a text to its comparison key.
   *
   * @param text the text, or {@code null}
   * @return the stripped, lower-cased text, empty when absent
   */
  @NotNull
  static String fold(@Nullable String text) {
    return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
  }
}
