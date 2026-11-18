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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kManufacturerDto;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The manufacturer rules of the P4K match (REQ-P4K-058). A row holding a brand's sibling moves to
 * the brand's primary while no row holds it, and a row holding a record that borrows another
 * brand's name moves to that brand while it is free and the row carries its name. A brand's
 * siblings are covered by the row of the brand.
 */
final class P4kManufacturerRules implements P4kMatchRules<Manufacturer> {

  @NotNull private final P4kManufacturerFamilies families;
  @NotNull private final Function<UUID, Optional<Manufacturer>> byP4kUuid;
  @NotNull private final Set<UUID> held;

  /**
   * Captures the brands and the GUIDs the stored manufacturers hold when the run starts.
   *
   * @param families the catalogue's brands
   * @param rows the stored manufacturers
   * @param byP4kUuid finds the stored manufacturer holding a P4K GUID
   */
  P4kManufacturerRules(
      @NotNull P4kManufacturerFamilies families,
      @NotNull Collection<Manufacturer> rows,
      @NotNull Function<UUID, Optional<Manufacturer>> byP4kUuid) {
    this.families = families;
    this.byP4kUuid = byP4kUuid;
    Set<UUID> guids = new HashSet<>();
    rows.forEach(row -> {
      if (row.getP4kUuid() != null) {
        guids.add(row.getP4kUuid());
      }
    });
    this.held = guids;
  }

  /**
   * Returns the brand a row moves to: the primary of the sibling it holds, or the brand whose name
   * the record it holds borrows when the row carries that name.
   *
   * @param row a stored manufacturer
   * @return the GUID the row follows, or {@code null} when it keeps the one it holds
   */
  @Override
  @Nullable
  public UUID followedGuid(@NotNull Manufacturer row) {
    UUID guid = row.getP4kUuid();
    if (families.isSibling(guid)) {
      UUID primary = Objects.requireNonNull(families.primaryOf(guid));
      return held.contains(primary) ? null : primary;
    }
    UUID lender = families.lenderOf(guid);
    P4kManufacturerDto brand = families.record(lender);
    if (lender == null
        || brand == null
        || held.contains(lender)
        || !P4kManufacturerFamilies.fold(brand.nameEn())
            .equals(P4kManufacturerFamilies.fold(row.getName()))) {
      return null;
    }
    return lender;
  }

  /**
   * Finds the row that moves to a brand's primary or lender.
   *
   * @param guid the brand's GUID
   * @return the row holding one of its siblings or borrowers and following it, empty otherwise
   */
  @Override
  @NotNull
  public Optional<Manufacturer> follower(@NotNull UUID guid) {
    return Stream.concat(families.membersOf(guid).stream(), families.borrowersOf(guid).stream())
        .filter(other -> !other.equals(guid))
        .map(byP4kUuid)
        .flatMap(Optional::stream)
        .filter(row -> guid.equals(followedGuid(row)))
        .findFirst();
  }

  /**
   * Tells whether a row stands for a sibling of its brand.
   *
   * @param guid the sibling's GUID
   * @param row a found manufacturer
   * @return {@code true} when the record is a sibling and the row belongs to its brand
   */
  @Override
  public boolean covers(@NotNull UUID guid, @NotNull Manufacturer row) {
    if (!families.isSibling(guid)) {
      return false;
    }
    UUID followed = followedGuid(row);
    UUID own = followed != null ? followed : row.getP4kUuid();
    UUID primary = families.primaryOf(guid);
    return primary != null && primary.equals(families.primaryOf(own));
  }
}
