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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One link of the navigation: a page, a quick-access page or a quick-access action, identified by a
 * stable entry key (REQ-UI-026, REQ-UI-032).
 *
 * <p>An entry key is two or more dot-separated segments, each a lower-case letter followed by
 * lower-case letters or digits, at most {@value #KEY_MAX_LENGTH} characters (e.g. {@code
 * missions.list}). It never encodes the URL path, so a page keeps its key when its path changes.
 *
 * @param key the stable entry key
 * @param href the context-relative path, a page-relative query, or an absolute URL for {@link
 *     NavigationTrait#EXTERNAL} entries
 * @param labelKey the message key of the label
 * @param keywordsKey the message key of the search terms, or {@code null} for none
 * @param testId the {@code data-testid} of the link, or {@code null} for none
 * @param icon the sprite icon name the quick access shows, or {@code null} for the group's
 * @param gate the condition under which the entry renders, on top of its group's
 * @param traits the rendering traits
 */
public record NavigationEntry(
    @NotNull String key,
    @NotNull String href,
    @NotNull String labelKey,
    @Nullable String keywordsKey,
    @Nullable String testId,
    @Nullable String icon,
    @NotNull NavigationGate gate,
    @NotNull Set<NavigationTrait> traits) {

  /** The longest entry key the format allows. */
  public static final int KEY_MAX_LENGTH = 64;

  private static final Pattern KEY_FORMAT = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+");

  /**
   * Refuses a malformed key and copies the traits.
   *
   * @param key the stable entry key
   * @param href the link target
   * @param labelKey the message key of the label
   * @param keywordsKey the message key of the search terms, or {@code null}
   * @param testId the {@code data-testid}, or {@code null}
   * @param icon the quick-access icon, or {@code null}
   * @param gate the entry's own condition
   * @param traits the rendering traits
   * @throws IllegalArgumentException if the key does not follow the entry-key format
   */
  public NavigationEntry {
    if (!isValidKey(key)) {
      throw new IllegalArgumentException("Malformed navigation entry key: " + key);
    }
    traits = traits.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(traits));
  }

  /**
   * Starts an ungated entry without search terms, test id, icon or traits.
   *
   * @param key the stable entry key
   * @param href the link target
   * @param labelKey the message key of the label
   * @return the entry
   */
  @NotNull
  public static NavigationEntry of(
      @NotNull String key, @NotNull String href, @NotNull String labelKey) {
    return new NavigationEntry(
        key, href, labelKey, null, null, null, NavigationGate.ALWAYS, Set.of());
  }

  /**
   * Whether a string follows the entry-key format.
   *
   * @param key the candidate key
   * @return {@code true} iff the key is well-formed and not longer than {@value #KEY_MAX_LENGTH}
   */
  public static boolean isValidKey(@Nullable String key) {
    return key != null && key.length() <= KEY_MAX_LENGTH && KEY_FORMAT.matcher(key).matches();
  }

  /**
   * This entry with search terms.
   *
   * @param messageKey the message key of the search terms
   * @return the changed entry
   */
  @NotNull
  public NavigationEntry keywords(@NotNull String messageKey) {
    return new NavigationEntry(key, href, labelKey, messageKey, testId, icon, gate, traits);
  }

  /**
   * This entry with a {@code data-testid}.
   *
   * @param id the test id
   * @return the changed entry
   */
  @NotNull
  public NavigationEntry testId(@NotNull String id) {
    return new NavigationEntry(key, href, labelKey, keywordsKey, id, icon, gate, traits);
  }

  /**
   * This entry with its own icon.
   *
   * @param name the sprite icon name
   * @return the changed entry
   */
  @NotNull
  public NavigationEntry icon(@NotNull String name) {
    return new NavigationEntry(key, href, labelKey, keywordsKey, testId, name, gate, traits);
  }

  /**
   * This entry under a condition.
   *
   * @param condition the entry's own gate
   * @return the changed entry
   */
  @NotNull
  public NavigationEntry gate(@NotNull NavigationGate condition) {
    return new NavigationEntry(key, href, labelKey, keywordsKey, testId, icon, condition, traits);
  }

  /**
   * This entry with further traits.
   *
   * @param more the added traits
   * @return the changed entry
   */
  @NotNull
  public NavigationEntry with(@NotNull NavigationTrait... more) {
    EnumSet<NavigationTrait> all = EnumSet.noneOf(NavigationTrait.class);
    all.addAll(traits);
    all.addAll(Set.of(more));
    return new NavigationEntry(key, href, labelKey, keywordsKey, testId, icon, gate, all);
  }

  /**
   * Whether the entry is offered only as a quick-access page.
   *
   * @return {@code true} iff the entry has {@link NavigationTrait#PALETTE_ONLY}
   */
  public boolean paletteOnly() {
    return traits.contains(NavigationTrait.PALETTE_ONLY);
  }

  /**
   * Whether the quick-access index skips the entry.
   *
   * @return {@code true} iff the entry has {@link NavigationTrait#NOT_INDEXED}
   */
  public boolean notIndexed() {
    return traits.contains(NavigationTrait.NOT_INDEXED);
  }

  /**
   * Whether the entry leaves the app in a new tab.
   *
   * @return {@code true} iff the entry has {@link NavigationTrait#EXTERNAL}
   */
  public boolean external() {
    return traits.contains(NavigationTrait.EXTERNAL);
  }

  /**
   * Whether the entry shows the unread notification count.
   *
   * @return {@code true} iff the entry has {@link NavigationTrait#UNREAD_BADGE}
   */
  public boolean unreadBadge() {
    return traits.contains(NavigationTrait.UNREAD_BADGE);
  }

  /**
   * Whether the entry renders its icon after the label.
   *
   * @return {@code true} iff the entry has {@link NavigationTrait#LABEL_WITH_ICON}
   */
  public boolean labelWithIcon() {
    return traits.contains(NavigationTrait.LABEL_WITH_ICON);
  }
}
