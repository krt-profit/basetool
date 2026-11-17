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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Decides between UEX terminals that match a shop's name equally well (REQ-P4K-043). Where they
 * differ in UEX type, the shop's commodity facts pick one: a commodity exchange or terminal, or a
 * shop of no known kind whose names say {@code commodit}, takes the {@code commodity} or {@code
 * commodity_raw} terminal, any other shop the {@code item} terminal. Else the district a terminal
 * names between its shop part and its place picks one, when the district or one of its words of at
 * least five letters is part of a container the shop is placed in.
 */
final class ShopTerminalTieBreaks {

  /** The UEX types of a commodity terminal. */
  private static final Set<String> COMMODITY_TYPES = Set.of("commodity", "commodity_raw");

  /** The UEX type of an item terminal. */
  private static final String ITEM_TYPE = "item";

  /** The stem a commodity shop's compact names hold. */
  private static final String COMMODITY_STEM = "commodit";

  /** The shortest district word that picks a terminal; shorter ones are generic. */
  private static final int MIN_DISTRICT_WORD = 5;

  /** The separator between the words of a normalised name. */
  private static final String WORD_SEPARATOR = " ";

  /** The number of parts of a terminal name with a district: shop, district, place. */
  private static final int PARTS_WITH_DISTRICT = 3;

  /** Non-instantiable holder of static helpers. */
  private ShopTerminalTieBreaks() {}

  /**
   * Narrows tied terminals to one by type, else by district.
   *
   * @param shop the shop
   * @param containers the containers the shop is placed in, from the system root down
   * @param candidates the terminals that match the shop's name equally well
   * @return the one terminal a tie-break picks, else the candidates
   */
  @NotNull
  @Unmodifiable
  static List<StarmapPlaceRow> pick(
      @NotNull Shop shop,
      @NotNull List<String> containers,
      @NotNull List<StarmapPlaceRow> candidates) {
    if (candidates.size() < 2) {
      return candidates;
    }
    List<StarmapPlaceRow> byType = byType(shop, candidates);
    if (byType.size() == 1) {
      return byType;
    }
    List<StarmapPlaceRow> byDistrict = byDistrict(containers, candidates);
    return byDistrict.size() == 1 ? byDistrict : candidates;
  }

  /**
   * Keeps the terminals of the UEX type the shop's commodity facts call for.
   *
   * @param shop the shop
   * @param candidates the tied terminals
   * @return those of the wanted type, empty when the candidates do not differ in type
   */
  @NotNull
  private static List<StarmapPlaceRow> byType(
      @NotNull Shop shop, @NotNull List<StarmapPlaceRow> candidates) {
    if (candidates.stream().map(StarmapPlaceRow::terminalType).distinct().count() < 2) {
      return List.of();
    }
    boolean commodity =
        shop.isCommodityExchange() || (shop.getKind() == null && namesCommodity(shop));
    return candidates.stream()
        .filter(terminal -> {
          String type = terminal.terminalType();
          if (type == null) {
            return false;
          }
          return commodity ? COMMODITY_TYPES.contains(type) : ITEM_TYPE.equals(type);
        })
        .toList();
  }

  /**
   * Whether a shop's compact registry or entity name holds the commodity stem.
   *
   * @param shop the shop
   * @return {@code true} when one of them does
   */
  private static boolean namesCommodity(@NotNull Shop shop) {
    return Stream.of(shop.getRegistryName(), shop.getEntityName())
        .map(StarmapNames::compact)
        .filter(Objects::nonNull)
        .anyMatch(name -> name.contains(COMMODITY_STEM));
  }

  /**
   * Keeps the terminals whose district is part of a container the shop is placed in.
   *
   * @param containers the shop's containers
   * @param candidates the tied terminals
   * @return those of the shop's district
   */
  @NotNull
  private static List<StarmapPlaceRow> byDistrict(
      @NotNull List<String> containers, @NotNull List<StarmapPlaceRow> candidates) {
    String held =
        containers.stream()
            .map(StarmapNames::compact)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(" "));
    return candidates.stream().filter(terminal -> inDistrict(terminal.name(), held)).toList();
  }

  /**
   * Whether a terminal's district is part of the shop's containers.
   *
   * @param name the terminal's UEX name, {@code <shop> - <district> - <place>}, or {@code null}
   * @param held the shop's compact containers, separated by spaces
   * @return {@code true} when the compact district or one of its long words occurs in them
   */
  private static boolean inDistrict(@Nullable String name, @NotNull String held) {
    if (name == null) {
      return false;
    }
    String[] parts = name.split(Pattern.quote(ShopTerminalNames.PLACE_SEPARATOR), -1);
    if (parts.length < PARTS_WITH_DISTRICT) {
      return false;
    }
    String district = StarmapNames.normalise(parts[1]);
    if (district == null) {
      return false;
    }
    return held.contains(district.replace(WORD_SEPARATOR, ""))
        || Arrays.stream(district.split(WORD_SEPARATOR))
            .filter(word -> word.length() >= MIN_DISTRICT_WORD)
            .anyMatch(held::contains);
  }
}
