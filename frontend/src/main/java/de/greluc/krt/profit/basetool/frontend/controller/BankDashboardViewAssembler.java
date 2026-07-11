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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankDashboardAccountDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Assembles the bank-dashboard view shapes (REQ-BANK-016) that {@code BankPageController} renders:
 * the per-account {@link BankDashboardCardView} (backend payload plus its pre-computed sparkline)
 * and the by-Bereich chunking into {@link BankDashboardGroupView}s. Extracted verbatim from the
 * 684-line {@code BankPageController} (audit L-tier controller de-bloat) into a small static
 * view-helper, co-located with the sibling bank view helpers {@code BankAccountOrder} and {@code
 * BankSparkline} and using the latter for the sparkline. Pure, stateless transforms — no backend
 * call or model mutation — so the controller keeps orchestration and this holds the view shaping.
 */
public final class BankDashboardViewAssembler {

  private BankDashboardViewAssembler() {}

  /**
   * One dashboard card: the backend account payload plus its pre-computed sparkline polyline.
   *
   * @param account the backend card payload
   * @param sparklinePoints SVG polyline {@code points} attribute value scaled to the 96x26 viewBox;
   *     {@code null} when the series is empty
   * @param flat {@code true} when the 30-day series never changes (renders the muted flat line)
   */
  public record BankDashboardCardView(
      BankDashboardAccountDto account, String sparklinePoints, boolean flat) {}

  /**
   * One vertical group of the by-Bereich dashboard view (REQ-BANK-016): a coloured header plus the
   * cards that belong to it. Fixed groups (KRT rubric, Sonderkonten, Ohne Bereich, Geschlossen)
   * carry an i18n {@code titleKey}; a Bereich group carries the live {@code bereichName} and a
   * {@code deptClass} (its Bereichsfarbe token class), both {@code null} for the fixed groups.
   *
   * @param key stable DOM/testing key ({@code krt} / {@code bereich:<id>} / {@code special} /
   *     {@code ungrouped} / {@code closed})
   * @param titleKey the i18n key for a fixed group's heading, or {@code null} for a Bereich group
   * @param bereichName the Bereich's display name for a Bereich group, or {@code null}
   * @param deptClass the Bereich department colour class ({@code bank-dept--<dept>}), or {@code
   *     null}
   * @param cards the group's cards, ordered
   */
  public record BankDashboardGroupView(
      String key,
      String titleKey,
      String bereichName,
      String deptClass,
      List<BankDashboardCardView> cards) {}

  /**
   * Wraps one backend card payload with its scaled sparkline polyline ({@link BankSparkline}),
   * ready for the template.
   *
   * @param account the backend card payload
   * @return the card view with pre-computed points
   */
  public static BankDashboardCardView toCardView(@NotNull BankDashboardAccountDto account) {
    BankSparkline.Spark spark = BankSparkline.of(account.sparkline());
    return new BankDashboardCardView(account, spark.points(), spark.flat());
  }

  /**
   * Chunks the A→Z card list into the by-Bereich groups (REQ-BANK-016), in display order: the KRT
   * rubric (CARTEL + KRT-bank), one group per Bereich (A→Z by Bereich name, each holding its AREA
   * account first then its Staffel/SK accounts A→Z), the Sonderkonten, the "Ohne Bereich" bucket
   * for org-unit accounts with no Bereich, and finally every closed account. Only non-empty groups
   * are emitted. The input order (A→Z by account name) is preserved within the KRT / Sonderkonten /
   * closed buckets.
   *
   * @param cards the account cards, already ordered A→Z by name
   * @return the ordered, non-empty groups for the by-Bereich view
   */
  public static List<BankDashboardGroupView> buildGroups(List<BankDashboardCardView> cards) {
    List<BankDashboardCardView> krt = new ArrayList<>();
    List<BankDashboardCardView> special = new ArrayList<>();
    List<BankDashboardCardView> ungrouped = new ArrayList<>();
    List<BankDashboardCardView> closed = new ArrayList<>();
    Map<UUID, List<BankDashboardCardView>> byBereich = new LinkedHashMap<>();
    Map<UUID, BankDashboardCardView> bereichMeta = new HashMap<>();
    for (BankDashboardCardView card : cards) {
      BankDashboardAccountDto account = card.account();
      if ("CLOSED".equals(account.status())) {
        closed.add(card);
        continue;
      }
      switch (account.type()) {
        case "CARTEL", "CARTEL_BANK" -> krt.add(card);
        case "SPECIAL" -> special.add(card);
        default -> {
          if (account.bereichId() != null) {
            byBereich.computeIfAbsent(account.bereichId(), k -> new ArrayList<>()).add(card);
            bereichMeta.putIfAbsent(account.bereichId(), card);
          } else {
            ungrouped.add(card);
          }
        }
      }
    }
    List<BankDashboardGroupView> groups = new ArrayList<>();
    if (!krt.isEmpty()) {
      groups.add(new BankDashboardGroupView("krt", "bank.dashboard.group.krt", null, null, krt));
    }
    byBereich.entrySet().stream()
        .sorted(
            Comparator.comparing(
                entry -> bereichNameOf(bereichMeta.get(entry.getKey())),
                String.CASE_INSENSITIVE_ORDER))
        .forEach(
            entry -> {
              BankDashboardCardView meta = bereichMeta.get(entry.getKey());
              List<BankDashboardCardView> groupCards = new ArrayList<>(entry.getValue());
              groupCards.sort(
                  Comparator.<BankDashboardCardView, Integer>comparing(
                          card -> "AREA".equals(card.account().type()) ? 0 : 1)
                      .thenComparing(card -> card.account().name(), String.CASE_INSENSITIVE_ORDER));
              groups.add(
                  new BankDashboardGroupView(
                      "bereich:" + entry.getKey(),
                      null,
                      meta.account().bereichName(),
                      deptClass(meta.account().bereichDepartment()),
                      groupCards));
            });
    if (!special.isEmpty()) {
      groups.add(
          new BankDashboardGroupView(
              "special", "bank.dashboard.group.special", null, null, special));
    }
    if (!ungrouped.isEmpty()) {
      groups.add(
          new BankDashboardGroupView(
              "ungrouped", "bank.dashboard.group.ungrouped", null, null, ungrouped));
    }
    if (!closed.isEmpty()) {
      groups.add(
          new BankDashboardGroupView("closed", "bank.dashboard.group.closed", null, null, closed));
    }
    return groups;
  }

  /**
   * The Bereich name of a card, or an empty string when absent — the sort key for ordering the
   * Bereich groups so a null name never trips the case-insensitive comparator.
   *
   * @param card any card of the Bereich group
   * @return the Bereich display name, or {@code ""} when unknown
   */
  private static String bereichNameOf(@Nullable BankDashboardCardView card) {
    String name = card == null ? null : card.account().bereichName();
    return name == null ? "" : name;
  }

  /**
   * Maps a Bereich department enum name to its design-system colour class ({@code
   * bank-dept--<dept>} over the {@code --color-dept-*} tokens), mirroring the org chart's {@code
   * oc-dept--*} convention.
   *
   * @param department the Bereich's department enum name, or {@code null}
   * @return the colour class, or {@code null} when the Bereich has no department
   */
  private static String deptClass(@Nullable String department) {
    return department == null
        ? null
        : "bank-dept--" + department.toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
