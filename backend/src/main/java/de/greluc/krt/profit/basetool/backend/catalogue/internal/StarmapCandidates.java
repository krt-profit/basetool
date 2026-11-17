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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Finds the starmap objects a UEX location row may link to (REQ-P4K-035). Each name of {@link
 * StarmapNameKeys#names} is tried in turn, by its normalised form, else by its compact form; its
 * objects of the row's type families are looked for below the row's nearest linked parent, else
 * below each further linked parent up to the star system, else the one object of the name in the
 * star system of any type is taken. A row still without a candidate takes the one object of its
 * type families in the star system whose name equals its own with {@code o} read as {@code 0}, else
 * the one below its nearest linked parent whose name is its own plus whole trailing words, or the
 * other way round. Of several candidates the only placed one wins. A stamped or hidden object is
 * never a candidate.
 */
final class StarmapCandidates {

  private final P4kStarmapTree tree;
  private final Map<String, List<StarmapObject>> byName = new HashMap<>();
  private final Map<String, List<StarmapObject>> byCompact = new HashMap<>();
  private final Map<String, List<StarmapObject>> byFolded = new HashMap<>();

  /**
   * Indexes the objects of a tree a row can link to by their name forms.
   *
   * @param tree the starmap tree after the run's reconciliation
   */
  StarmapCandidates(@NotNull P4kStarmapTree tree) {
    this.tree = tree;
    for (StarmapObject object : tree.all()) {
      if (!object.isP4kRetired()) {
        index(byName, StarmapNames.normalise(object.getName()), object);
        index(byCompact, StarmapNames.compact(object.getName()), object);
        index(byFolded, StarmapNameKeys.folded(object.getName()), object);
      }
    }
  }

  /**
   * Finds the candidates of a row.
   *
   * @param row the row
   * @param scopes the GUIDs of the row's linked parents, nearest first and its star system last;
   *     empty for a star system row, whose candidates are every object of its name and type
   * @return the GUIDs of the candidates: one links, several are ambiguous, none is no hit
   */
  @NotNull
  @Unmodifiable
  List<UUID> find(@NotNull StarmapPlaceRow row, @NotNull List<UUID> scopes) {
    UUID system = scopes.isEmpty() ? null : scopes.getLast();
    for (String name : StarmapNameKeys.names(row)) {
      List<StarmapObject> named = named(name);
      List<StarmapObject> typed = typed(row, named);
      List<StarmapObject> found = system == null ? placed(typed) : nearestScope(typed, scopes);
      if (!found.isEmpty()) {
        return guids(found);
      }
      List<StarmapObject> anyType = system == null ? List.of() : placed(below(named, system));
      if (anyType.size() == 1) {
        return guids(anyType);
      }
    }
    return system == null ? List.of() : spelledApart(row, scopes.getFirst(), system);
  }

  /**
   * Finds a row's one candidate by a spelling of its name: with {@code o} read as {@code 0} in the
   * star system, else by whole trailing words below the nearest linked parent.
   *
   * @param row the row
   * @param nearest the GUID of the row's nearest linked parent
   * @param system the GUID of the row's star system
   * @return the GUID of the one candidate, or nothing
   */
  @NotNull
  @Unmodifiable
  private List<UUID> spelledApart(
      @NotNull StarmapPlaceRow row, @NotNull UUID nearest, @NotNull UUID system) {
    String folded = StarmapNameKeys.folded(row.name());
    List<StarmapObject> byDigits =
        folded == null
            ? List.of()
            : placed(below(typed(row, byFolded.getOrDefault(folded, List.of())), system));
    if (byDigits.size() == 1) {
      return guids(byDigits);
    }
    List<StarmapObject> byWords = placed(below(typed(row, extensions(row.name())), nearest));
    return byWords.size() == 1 ? guids(byWords) : List.of();
  }

  /**
   * Returns the objects of a name: by its normalised form, else by its compact form.
   *
   * @param name the name
   * @return the objects, empty when neither form names one
   */
  @NotNull
  private List<StarmapObject> named(@NotNull String name) {
    String normalised = StarmapNames.normalise(name);
    List<StarmapObject> exact =
        normalised == null ? List.of() : byName.getOrDefault(normalised, List.of());
    if (!exact.isEmpty()) {
      return exact;
    }
    String compact = StarmapNames.compact(name);
    return compact == null ? List.of() : byCompact.getOrDefault(compact, List.of());
  }

  /**
   * Returns the objects whose normalised name is a row's plus whole trailing words, or the other
   * way round.
   *
   * @param name the row's name, or {@code null}
   * @return the objects
   */
  @NotNull
  private List<StarmapObject> extensions(@Nullable String name) {
    String normalised = StarmapNames.normalise(name);
    if (normalised == null) {
      return List.of();
    }
    List<StarmapObject> objects = new ArrayList<>();
    byName.forEach((other, named) -> {
      if (StarmapNameKeys.extendsByWords(normalised, other)) {
        objects.addAll(named);
      }
    });
    return objects;
  }

  /**
   * Returns the objects below the nearest scope that holds any.
   *
   * @param objects the objects
   * @param scopes the scopes, nearest first
   * @return the placed-first objects below the first scope holding one, or nothing
   */
  @NotNull
  private List<StarmapObject> nearestScope(
      @NotNull List<StarmapObject> objects, @NotNull List<UUID> scopes) {
    for (UUID scope : scopes) {
      List<StarmapObject> found = placed(below(objects, scope));
      if (!found.isEmpty()) {
        return found;
      }
    }
    return List.of();
  }

  /**
   * Keeps the objects of a row's type families.
   *
   * @param row the row
   * @param objects the objects
   * @return the objects the row's table accepts
   */
  @NotNull
  private static List<StarmapObject> typed(
      @NotNull StarmapPlaceRow row, @NotNull List<StarmapObject> objects) {
    return objects.stream().filter(o -> row.kind().accepts(o.getType())).toList();
  }

  /**
   * Keeps the objects below a scope, at any depth.
   *
   * @param objects the objects
   * @param scope the scope's GUID
   * @return the objects below it
   */
  @NotNull
  private List<StarmapObject> below(@NotNull List<StarmapObject> objects, @NotNull UUID scope) {
    return objects.stream().filter(o -> tree.isBelow(o.getP4kUuid(), scope)).toList();
  }

  /**
   * Narrows several objects to the only one the catalogue placed, as an unplaced record is a
   * duplicate of the placed one.
   *
   * @param objects the objects
   * @return the one placed object when exactly one of several is placed, else the objects
   */
  @NotNull
  private static List<StarmapObject> placed(@NotNull List<StarmapObject> objects) {
    if (objects.size() < 2) {
      return objects;
    }
    List<StarmapObject> placed = objects.stream().filter(StarmapObject::hasPosition).toList();
    return placed.size() == 1 ? placed : objects;
  }

  /**
   * Returns the GUIDs of objects.
   *
   * @param objects the objects
   * @return their GUIDs
   */
  @NotNull
  @Unmodifiable
  private static List<UUID> guids(@NotNull List<StarmapObject> objects) {
    return objects.stream().map(StarmapObject::getP4kUuid).toList();
  }

  /**
   * Adds an object to an index under a key.
   *
   * @param index the index
   * @param key the key, or {@code null} to leave the object out
   * @param object the object
   */
  private static void index(
      @NotNull Map<String, List<StarmapObject>> index,
      @Nullable String key,
      @NotNull StarmapObject object) {
    if (key != null) {
      index.computeIfAbsent(key, _ -> new ArrayList<>()).add(object);
    }
  }
}
