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

package de.greluc.krt.profit.basetool.frontend.shell.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.shell.web.QuickAccessController.QuickAccessItem;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

/** Unit tests for {@link QuickAccessController}: contributor order and message resolution. */
class QuickAccessControllerTest {

  /** Clears the locale the tests pin. */
  @AfterEach
  void resetLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  /** The entries of every contributor come back in contributor order, in the caller's language. */
  @Test
  void resolvesEveryContributorInOrder() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("a.label", Locale.ENGLISH, "Alpha");
    messages.addMessage("a.crumb", Locale.ENGLISH, "Group");
    messages.addMessage("a.keywords", Locale.ENGLISH, "first one");
    messages.addMessage("b.label", Locale.ENGLISH, "Beta");
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    QuickAccessController controller =
        new QuickAccessController(
            List.of(
                () ->
                    List.of(new QuickAccessEntry("/a", "a.label", "a.crumb", "list", "a.keywords")),
                () ->
                    List.of(
                        new QuickAccessEntry("/b", "b.label", "a.crumb", "gear", "b.keywords"))),
            messages,
            Set::of);

    assertThat(controller.entries())
        .containsExactly(
            new QuickAccessItem("/a", "Alpha", "Group", "list", "first one"),
            new QuickAccessItem("/b", "Beta", "Group", "gear", ""));
  }

  /** An entry of a hidden page is left out; entries of other pages and of no page stay. */
  @Test
  void leavesOutTheEntriesOfAHiddenPage() {
    StaticMessageSource messages = new StaticMessageSource();
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    QuickAccessController controller =
        new QuickAccessController(
            List.of(() ->
                List.of(
                    new QuickAccessEntry("/s", "s.label", "c", "list", "k", "supply.needs"),
                    new QuickAccessEntry("/p", "p.label", "c", "list", "k", "bank.dashboard"),
                    new QuickAccessEntry("/n", "n.label", "c", "list", "k"))),
            messages,
            () -> Set.of("supply.needs"));

    assertThat(controller.entries()).extracting(QuickAccessItem::href).containsExactly("/p", "/n");
  }

  /** Without contributors, or when none offers anything, the answer is empty. */
  @Test
  void answersEmptyWithoutEntries() {
    QuickAccessController controller =
        new QuickAccessController(List.of(List::of), new StaticMessageSource(), Set::of);

    assertThat(controller.entries()).isEmpty();
  }
}
