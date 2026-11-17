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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the commodity, resource-group and resource-type records the commodity rule tests add to
 * the trimmed fixture, whose own commodities carry no resource types.
 */
final class P4kCommodityFixtures {

  /** The fixture's Borase record, the template of an added commodity. */
  static final UUID BORASE = UUID.fromString("001c7bc6-2bb6-4f66-a8ef-a1c63e80ffc5");

  /** Borase's {@code subtypeGuid}, which no other record of the fixture carries. */
  static final UUID BORASE_TYPE = UUID.fromString("33bff393-42f1-4f70-85a1-71e695ed2a5a");

  /** The record path of every resource group and type. */
  private static final String DATABASE =
      "libs/foundry/records/resourcetypedatabase/resourcetypedatabase";

  /** Non-instantiable holder of static helpers. */
  private P4kCommodityFixtures() {}

  /**
   * Adds a commodity, a copy of Borase with its own identity.
   *
   * @param root the catalogue tree
   * @param guid the record GUID
   * @param className the class name
   * @param name the English name
   * @param subtype the {@code subtypeGuid}
   * @param group the {@code typeGuid}, or {@code null} for Borase's
   */
  static void commodity(
      @NotNull ObjectNode root,
      @NotNull UUID guid,
      @NotNull String className,
      @NotNull String name,
      @NotNull UUID subtype,
      @Nullable UUID group) {
    ObjectNode copy = P4kTestCatalogues.record(root, "commodities", BORASE).deepCopy();
    copy.put("guid", guid.toString());
    copy.put("className", className);
    ((ObjectNode) copy.get("name")).put("en", name);
    copy.put("subtypeGuid", subtype.toString());
    if (group != null) {
      copy.put("typeGuid", group.toString());
    }
    ((ArrayNode) root.get("commodities")).add(copy);
  }

  /**
   * Adds a top-level resource group with an English name only.
   *
   * @param root the catalogue tree
   * @param guid the GUID
   * @param className the class name
   * @param name the English name
   */
  static void resourceGroup(
      @NotNull ObjectNode root,
      @NotNull UUID guid,
      @NotNull String className,
      @NotNull String name) {
    resourceGroup(root, guid, className, name, null);
  }

  /**
   * Adds a top-level resource group.
   *
   * @param root the catalogue tree
   * @param guid the GUID
   * @param className the class name
   * @param name the English name, or {@code null} for the placeholder name
   * @param nameDe the German name, or {@code null}
   */
  static void resourceGroup(
      @NotNull ObjectNode root,
      @NotNull UUID guid,
      @NotNull String className,
      @Nullable String name,
      @Nullable String nameDe) {
    ObjectNode group = ((ArrayNode) root.get("resourceGroups")).addObject();
    group.put("guid", guid.toString());
    group.put("className", className);
    group.put("recordPath", DATABASE);
    name(group, name, nameDe);
    group.putNull("parentGuid");
  }

  /**
   * Adds a resource type, creating the {@code resourceTypes} kind the fixture lacks.
   *
   * @param root the catalogue tree
   * @param guid the GUID
   * @param className the class name
   * @param name the English name
   * @param group the one group that lists it, or {@code null} for none
   */
  static void resourceType(
      @NotNull ObjectNode root,
      @NotNull UUID guid,
      @NotNull String className,
      @NotNull String name,
      @Nullable UUID group) {
    JsonNode kind = root.get("resourceTypes");
    ArrayNode types = kind == null ? root.putArray("resourceTypes") : (ArrayNode) kind;
    ObjectNode type = types.addObject();
    type.put("guid", guid.toString());
    type.put("className", className);
    type.put("recordPath", DATABASE);
    name(type, name, null);
    ArrayNode groups = type.putArray("groupGuids");
    if (group != null) {
      groups.add(group.toString());
    }
  }

  /**
   * Writes a localised name; an absent English name is the placeholder key.
   *
   * @param record the record
   * @param name the English name, or {@code null}
   * @param nameDe the German name, or {@code null}
   */
  private static void name(
      @NotNull ObjectNode record, @Nullable String name, @Nullable String nameDe) {
    ObjectNode text = record.putObject("name");
    text.put("key", name == null ? "@LOC_PLACEHOLDER" : "@test_" + name.replace(' ', '_'));
    text.put("en", name);
    text.put("de", nameDe);
  }
}
