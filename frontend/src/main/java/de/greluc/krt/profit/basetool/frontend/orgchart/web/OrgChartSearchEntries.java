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

package de.greluc.krt.profit.basetool.frontend.orgchart.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessFold;
import de.greluc.krt.profit.basetool.frontend.leadership.model.AreaLeadershipDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.BereichChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.CommandChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OlChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartNodeDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.SpecialCommandChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.SquadronChartDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Flattens the org chart read into the units and the people it shows, so the quick access finds
 * exactly what the chart page displays and nothing more (REQ-UI-033).
 */
final class OrgChartSearchEntries {

  private static final String COMMAND_LEAD = "COMMAND_LEAD";

  private OrgChartSearchEntries() {}

  /**
   * One unit of the chart.
   *
   * @param id the unit id, which the chart page highlights
   * @param name the unit's name
   * @param shorthand the unit's short tag, possibly {@code null}
   */
  record UnitEntry(@NotNull UUID id, @NotNull String name, @Nullable String shorthand) {}

  /**
   * One person of the chart.
   *
   * @param highlightKey the user id, or the position id of a holder without an account, which the
   *     chart page highlights; {@code null} when the chart gives neither
   * @param name the name the chart shows
   * @param positionType the first rank the person holds on the chart
   */
  record PersonEntry(
      @Nullable UUID highlightKey, @NotNull String name, @NotNull String positionType) {}

  /**
   * The units of the chart that match a term, those whose name starts with it first.
   *
   * @param chart the chart read, possibly {@code null}
   * @param term the search term
   * @param limit the most entries
   * @return the matching units, in chart order within each group
   */
  static @NotNull List<UnitEntry> matchingUnits(
      @Nullable OrgChartDto chart, @NotNull String term, int limit) {
    String folded = QuickAccessFold.fold(term);
    return units(chart)
        .filter(u ->
            QuickAccessFold.matches(u.name(), folded)
                || (u.shorthand() != null && QuickAccessFold.matches(u.shorthand(), folded)))
        .sorted(Comparator.comparing(u -> !QuickAccessFold.startsWith(u.name(), folded)))
        .limit(limit)
        .toList();
  }

  /**
   * The people of the chart whose name matches a term, those whose name starts with it first.
   *
   * @param chart the chart read, possibly {@code null}
   * @param term the search term
   * @param limit the most entries
   * @return the matching people, one entry per person
   */
  static @NotNull List<PersonEntry> matchingPeople(
      @Nullable OrgChartDto chart, @NotNull String term, int limit) {
    String folded = QuickAccessFold.fold(term);
    Map<String, PersonEntry> byPerson = new LinkedHashMap<>();
    people(chart)
        .filter(p -> QuickAccessFold.matches(p.name(), folded))
        .forEach(p ->
            byPerson.putIfAbsent(
                p.highlightKey() != null ? p.highlightKey().toString() : "name:" + p.name(), p));
    return byPerson.values().stream()
        .sorted(Comparator.comparing(p -> !QuickAccessFold.startsWith(p.name(), folded)))
        .limit(limit)
        .toList();
  }

  private static @NotNull Stream<UnitEntry> units(@Nullable OrgChartDto chart) {
    if (chart == null) {
      return Stream.empty();
    }
    List<UnitEntry> out = new ArrayList<>();
    OlChartDto ol = chart.organisationsleitung();
    if (ol != null) {
      out.add(new UnitEntry(ol.orgUnitId(), ol.name(), ol.shorthand()));
    }
    for (BereichChartDto b : orEmpty(chart.bereiche())) {
      out.add(new UnitEntry(b.orgUnitId(), b.name(), b.shorthand()));
    }
    squadrons(chart).forEach(s -> out.add(new UnitEntry(s.orgUnitId(), s.name(), s.shorthand())));
    specialCommands(chart)
        .forEach(s -> out.add(new UnitEntry(s.orgUnitId(), s.name(), s.shorthand())));
    return out.stream();
  }

  private static @NotNull Stream<PersonEntry> people(@Nullable OrgChartDto chart) {
    if (chart == null) {
      return Stream.empty();
    }
    List<OrgChartNodeDto> nodes = new ArrayList<>();
    OlChartDto ol = chart.organisationsleitung();
    if (ol != null) {
      nodes.add(ol.grandAdmiral());
      nodes.addAll(orEmpty(ol.members()));
    }
    addLeadership(nodes, chart.areaLeadership());
    for (BereichChartDto b : orEmpty(chart.bereiche())) {
      addLeadership(nodes, b.leadership());
    }
    squadrons(chart).forEach(s -> addSquadron(nodes, s));
    specialCommands(chart).forEach(s -> nodes.addAll(orEmpty(s.commanders())));
    return nodes.stream()
        .filter(Objects::nonNull)
        .map(OrgChartSearchEntries::personOf)
        .filter(Objects::nonNull);
  }

  private static @NotNull Stream<SquadronChartDto> squadrons(@NotNull OrgChartDto chart) {
    return Stream.concat(
        orEmpty(chart.bereiche()).stream().flatMap(b -> orEmpty(b.squadrons()).stream()),
        orEmpty(chart.squadrons()).stream());
  }

  private static @NotNull Stream<SpecialCommandChartDto> specialCommands(
      @NotNull OrgChartDto chart) {
    return Stream.concat(
        orEmpty(chart.bereiche()).stream().flatMap(b -> orEmpty(b.specialCommands()).stream()),
        orEmpty(chart.specialCommands()).stream());
  }

  private static void addLeadership(
      @NotNull List<OrgChartNodeDto> nodes, @Nullable AreaLeadershipDto leadership) {
    if (leadership == null) {
      return;
    }
    nodes.add(leadership.lead());
    nodes.addAll(orEmpty(leadership.commanders()));
    nodes.addAll(orEmpty(leadership.coordinators()));
    nodes.addAll(orEmpty(leadership.operators()));
  }

  private static void addSquadron(
      @NotNull List<OrgChartNodeDto> nodes, @NotNull SquadronChartDto squadron) {
    nodes.add(squadron.lead());
    for (CommandChartDto c : orEmpty(squadron.commands())) {
      nodes.add(
          new OrgChartNodeDto(
              c.positionId(),
              COMMAND_LEAD,
              c.leaderUserId(),
              c.leaderUserName(),
              c.leaderDisplayName(),
              c.sortIndex(),
              c.version()));
      nodes.add(c.deputy());
      nodes.addAll(orEmpty(c.ensigns()));
    }
    nodes.addAll(orEmpty(squadron.directEnsigns()));
  }

  private static @Nullable PersonEntry personOf(@NotNull OrgChartNodeDto node) {
    String name = node.userName() != null ? node.userName() : node.displayName();
    if (name == null || name.isBlank()) {
      return null;
    }
    UUID key = node.userId() != null ? node.userId() : node.positionId();
    return new PersonEntry(key, name, node.positionType());
  }

  private static <T> @NotNull List<T> orEmpty(@Nullable List<T> list) {
    return list == null ? List.of() : list;
  }
}
