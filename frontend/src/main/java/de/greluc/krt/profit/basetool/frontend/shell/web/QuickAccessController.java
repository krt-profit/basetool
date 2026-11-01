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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHiddenEntries;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Answers the quick access's on-demand pages for the caller (REQ-UI-026, ADR-0282). */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class QuickAccessController {

  /** The endpoint path; mirrored by {@code data-entries-url} in {@code palette.html}. */
  public static final String PATH = "/quick-access/entries";

  private final List<QuickAccessContributor> contributors;
  private final MessageSource messageSource;
  private final QuickAccessHiddenEntries hiddenEntries;

  /**
   * One page of the answer, already in the caller's language.
   *
   * @param href the page's path
   * @param label its name
   * @param crumb the group it is shown under
   * @param icon the sprite symbol without its {@code krt-icon-} prefix
   * @param keywords additional search terms, space-separated
   */
  public record QuickAccessItem(
      @NotNull String href,
      @NotNull String label,
      @NotNull String crumb,
      @NotNull String icon,
      @NotNull String keywords) {}

  /**
   * Lists the pages every contributor offers the caller, in contributor order, without the tabs of
   * the pages the caller hid.
   *
   * @return the pages, possibly empty
   */
  @NotNull
  @GetMapping(value = PATH, headers = "X-Requested-With=XMLHttpRequest")
  public List<QuickAccessItem> entries() {
    Locale locale = LocaleContextHolder.getLocale();
    Set<String> hidden = hiddenEntries.keys();
    return contributors.stream()
        .flatMap(c -> c.entries().stream())
        .filter(e -> e.pageKey() == null || !hidden.contains(e.pageKey()))
        .map(e -> toItem(e, locale))
        .toList();
  }

  private @NotNull QuickAccessItem toItem(@NotNull QuickAccessEntry entry, @NotNull Locale locale) {
    return new QuickAccessItem(
        entry.href(),
        messageSource.getMessage(entry.labelKey(), null, entry.labelKey(), locale),
        messageSource.getMessage(entry.crumbKey(), null, entry.crumbKey(), locale),
        entry.icon(),
        messageSource.getMessage(entry.keywordsKey(), null, "", locale));
  }
}
