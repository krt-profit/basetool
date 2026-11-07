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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The table of each P4K kind as the sweep (REQ-P4K-011) and the review hide (REQ-P4K-016) reach it:
 * the JPA entity name and how a P4K-known row and its liveness in UEX and the SC Wiki read.
 */
@RequiredArgsConstructor
@Getter
enum P4kSweepTarget {

  /** {@code game_item}: live in UEX while it has a UEX id and no UEX stamp. */
  ITEM(
      P4kKind.ITEM,
      "GameItem",
      "e.className",
      "e.name",
      "(e.uexItemId IS NOT NULL AND e.uexDeletedAt IS NULL)"
          + " OR (e.scwikiSyncedAt IS NOT NULL AND e.scwikiDeletedAt IS NULL)"),

  /** {@code ship_type}. */
  SHIP(
      P4kKind.SHIP,
      "ShipType",
      "e.className",
      "e.name",
      "(e.uexVehicleId IS NOT NULL AND e.uexDeletedAt IS NULL)"
          + " OR (e.scwikiSyncedAt IS NOT NULL AND e.scwikiDeletedAt IS NULL)"),

  /** {@code manufacturer}; its abbreviation stands in for a class name. */
  MANUFACTURER(
      P4kKind.MANUFACTURER,
      "Manufacturer",
      "e.abbreviation",
      "e.name",
      "(e.uexCompanyId IS NOT NULL AND e.uexDeletedAt IS NULL)"
          + " OR (e.scwikiSyncedAt IS NOT NULL AND e.scwikiDeletedAt IS NULL)"),

  /** {@code material}: UEX keeps no stamp on commodities, so a UEX id reads as live. */
  COMMODITY(
      P4kKind.COMMODITY,
      "Material",
      "e.scwikiKey",
      "e.name",
      "e.idCommodity IS NOT NULL"
          + " OR (e.scwikiSyncedAt IS NOT NULL AND e.scwikiDeletedAt IS NULL)"),

  /** {@code blueprint}; its SC Wiki key stands in for a class name. */
  BLUEPRINT(
      P4kKind.BLUEPRINT,
      "Blueprint",
      "e.scwikiKey",
      "e.outputName",
      "e.scwikiSyncedAt IS NOT NULL AND e.scwikiDeletedAt IS NULL"),

  /**
   * {@code starmap_object}; its name is a game text, so the report names it by its class name, and
   * no other source lists it.
   */
  STARMAP_OBJECT(
      P4kKind.STARMAP_OBJECT, "StarmapObject", "e.className", "e.className", "e.id IS NULL"),

  /** {@code shop}; its entity name stands in for a class name, and no other source lists it. */
  SHOP(P4kKind.SHOP, "Shop", "e.entityName", "e.registryName", "e.id IS NULL"),

  /** {@code material_category}: no other source lists a resource group. */
  RESOURCE_GROUP(P4kKind.RESOURCE_GROUP, "MaterialCategory", "e.className", "e.name", "1 = 0"),

  /** {@code blueprint_category}: no other source lists a blueprint category. */
  BLUEPRINT_CATEGORY(
      P4kKind.BLUEPRINT_CATEGORY, "BlueprintCategory", "e.className", "e.className", "1 = 0");

  /** The kind. */
  @NotNull private final P4kKind kind;

  /** The JPA entity name. */
  @NotNull private final String entity;

  /** The JPQL path of the class name shown in the report. */
  @NotNull private final String classNamePath;

  /** The JPQL path of the name shown in the report. */
  @NotNull private final String namePath;

  /** The JPQL condition under which UEX or the SC Wiki still list the row as live. */
  @NotNull private final String liveElsewhere;

  /**
   * Returns the target of a kind.
   *
   * @param kind the kind
   * @return its target
   */
  @NotNull
  static P4kSweepTarget of(@NotNull P4kKind kind) {
    return switch (kind) {
      case ITEM -> ITEM;
      case SHIP -> SHIP;
      case MANUFACTURER -> MANUFACTURER;
      case COMMODITY -> COMMODITY;
      case BLUEPRINT -> BLUEPRINT;
      case STARMAP_OBJECT -> STARMAP_OBJECT;
      case SHOP -> SHOP;
      case RESOURCE_GROUP -> RESOURCE_GROUP;
      case BLUEPRINT_CATEGORY -> BLUEPRINT_CATEGORY;
    };
  }

  /**
   * The JPQL that reads every P4K-known row of the kind as a {@link P4kKnownRow}.
   *
   * @return the query text
   */
  @NotNull
  String knownRowsQuery() {
    return "SELECT new "
        + P4kKnownRow.class.getName()
        + "(e.id, e.p4kUuid, "
        + classNamePath
        + ", "
        + namePath
        + ", CASE WHEN e.p4kDeletedAt IS NULL THEN false ELSE true END,"
        + " CASE WHEN "
        + liveElsewhere
        + " THEN true ELSE false END) FROM "
        + entity
        + " e WHERE e.p4kUuid IS NOT NULL";
  }

  /**
   * The JPQL that reads the kind's live P4K-known rows, those no sweep stamped, as {@link
   * P4kKnownRow}s: the base of the sweep and its mass guard (REQ-P4K-012).
   *
   * @return the query text
   */
  @NotNull
  String liveRowsQuery() {
    return knownRowsQuery() + " AND e.p4kDeletedAt IS NULL";
  }

  /**
   * The JPQL that loads the rows of a set of ids.
   *
   * @return the query text with the parameter {@code ids}
   */
  @NotNull
  String rowsByIdQuery() {
    return "SELECT e FROM " + entity + " e WHERE e.id IN :ids";
  }

  /**
   * The JPQL that reads the id and the name of a set of rows.
   *
   * @return the query text with the parameter {@code ids}, selecting {@code [id, name]} tuples
   */
  @NotNull
  String rowNamesQuery() {
    return "SELECT e.id, " + namePath + " FROM " + entity + " e WHERE e.id IN :ids";
  }

  /**
   * The JPQL that counts the kind's rows a sweep stamped as gone (REQ-OBS-022).
   *
   * @return the query text, selecting one {@code Long}
   */
  @NotNull
  String stampedRowsCountQuery() {
    return "SELECT COUNT(e) FROM "
        + entity
        + " e WHERE e.p4kUuid IS NOT NULL AND e.p4kDeletedAt IS NOT NULL";
  }
}
