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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Replaces other members' handles in free text going into a data export (REQ-SEC-058).
 *
 * <p>Scans the original text in one forward pass, case-insensitively via {@link
 * String#regionMatches(boolean, int, String, int, int)}, taking the longest term at each position.
 * A match must be flanked by non-alphanumeric characters, handles shorter than {@value
 * #MIN_HANDLE_LENGTH} characters are skipped, and the subject's own names are matched but emitted
 * verbatim. Unknown persons, nicknames and misspellings are left to the admin review.
 */
public final class HandleScrubber {

  /** Shortest handle that is safe to replace by substring match. */
  public static final int MIN_HANDLE_LENGTH = 3;

  /**
   * The token that replaces a removed third-party handle; distinct from {@link
   * HandleAnonymisation#SENTINEL}.
   */
  public static final String REPLACEMENT = "#OTHER_MEMBER#";

  /**
   * One term the matcher recognises.
   *
   * @param text the term as the dictionary spells it
   * @param replaced whether a match is replaced ({@code true}) or emitted verbatim ({@code false})
   */
  private record Term(String text, boolean replaced) {}

  /** Every term to look for, longest first, so a longer match always beats a shorter one. */
  private final List<Term> terms;

  /**
   * The terms bucketed under every case-folding of their first character, longest first, so a
   * position is tested only against terms that could start there.
   */
  private final Map<Character, List<Term>> byFirstChar;

  /**
   * Creates a scrubber for the given third-party handles.
   *
   * @param otherHandles every other member's name in every spelling the schema stores
   */
  public HandleScrubber(@NotNull Collection<String> otherHandles) {
    this(otherHandles, List.of());
  }

  /**
   * Creates a scrubber that replaces one set of names and protects another.
   *
   * @param otherHandles every other member's name in every stored spelling; each occurrence is
   *     replaced by {@link #REPLACEMENT}
   * @param ownNames the subject's own names in every spelling; matched and emitted verbatim
   */
  public HandleScrubber(
      @NotNull Collection<String> otherHandles, @NotNull Collection<String> ownNames) {
    List<Term> collected = new ArrayList<>();
    addTerms(collected, otherHandles, true);
    addTerms(collected, ownNames, false);
    this.terms =
        collected.stream()
            .sorted((a, b) -> Integer.compare(b.text().length(), a.text().length()))
            .toList();

    Map<Character, List<Term>> buckets = new HashMap<>();
    for (Term term : this.terms) {
      for (char first : caseFoldings(term.text().charAt(0))) {
        buckets.computeIfAbsent(first, k -> new ArrayList<>()).add(term);
      }
    }
    this.byFirstChar = Map.copyOf(buckets);
  }

  /**
   * Adds the usable spellings of one side to the term list.
   *
   * @param into the list being built
   * @param names the raw candidates, possibly containing nulls and blanks
   * @param replaced whether these terms are replaced or protected
   */
  private static void addTerms(
      @NotNull List<Term> into, @NotNull Collection<String> names, boolean replaced) {
    names.stream()
        .filter(name -> name != null && name.trim().length() >= MIN_HANDLE_LENGTH)
        .map(String::trim)
        .distinct()
        .filter(name -> into.stream().noneMatch(t -> t.text().equalsIgnoreCase(name)))
        .forEach(name -> into.add(new Term(name, replaced)));
  }

  /**
   * Returns every character a case-insensitive {@code regionMatches} could accept in place of this
   * one.
   *
   * @param first the character to fold
   * @return the distinct characters it can stand in for, itself included
   */
  private static @NotNull Set<Character> caseFoldings(char first) {
    Set<Character> out = new LinkedHashSet<>();
    out.add(first);
    out.add(Character.toLowerCase(first));
    out.add(Character.toUpperCase(first));
    out.add(Character.toLowerCase(Character.toUpperCase(first)));
    out.add(Character.toUpperCase(Character.toLowerCase(first)));
    return out;
  }

  /**
   * Replaces every occurrence of a known third-party name in the text, case-insensitively
   * (REQ-SEC-060).
   *
   * @param text the free text, possibly {@code null}
   * @return the text with third-party names replaced, or {@code null} when the input was
   */
  public @Nullable String scrub(@Nullable String text) {
    if (text == null || text.isEmpty() || terms.isEmpty()) {
      return text;
    }
    StringBuilder out = null;
    int copiedUpTo = 0;
    int at = 0;
    while (at < text.length()) {
      Term hit = termAt(text, at);
      if (hit == null) {
        at++;
        continue;
      }
      if (out == null) {
        out = new StringBuilder(text.length());
      }
      out.append(text, copiedUpTo, at);
      if (hit.replaced()) {
        out.append(REPLACEMENT);
      } else {
        out.append(text, at, at + hit.text().length());
      }
      at += hit.text().length();
      copiedUpTo = at;
    }
    if (out == null) {
      return text;
    }
    return out.append(text, copiedUpTo, text.length()).toString();
  }

  /**
   * Returns the longest known term that starts at this position, ignoring case and flanked by
   * boundaries, looking up every {@link #caseFoldings(char)} of the text's character.
   *
   * @param text the text being scanned
   * @param at the position to test
   * @return the longest matching term, or {@code null} when none starts here
   */
  private @Nullable Term termAt(@NotNull String text, int at) {
    Term best = null;
    for (char folded : caseFoldings(text.charAt(at))) {
      List<Term> candidates = byFirstChar.get(folded);
      if (candidates == null) {
        continue;
      }
      for (Term term : candidates) {
        if (best != null && term.text().length() <= best.text().length()) {
          break;
        }
        int end = at + term.text().length();
        if (end <= text.length()
            && text.regionMatches(true, at, term.text(), 0, term.text().length())
            && isBoundary(text, at - 1)
            && isBoundary(text, end)) {
          best = term;
          break;
        }
      }
    }
    return best;
  }

  /**
   * Whether the character at this index does not continue a word.
   *
   * <p>Outside the string counts as a boundary: a name at the very start or end of a value is a
   * mention, not a fragment of something longer.
   *
   * @param text the text being scanned
   * @param index the position to inspect, possibly outside the string
   * @return {@code true} when the position is a word boundary
   */
  private static boolean isBoundary(@NotNull String text, int index) {
    return index < 0 || index >= text.length() || !Character.isLetterOrDigit(text.charAt(index));
  }

  /**
   * Whether this scrubber would change anything at all.
   *
   * <p>Asks about <em>replaceable</em> terms only: a scrubber holding nothing but the subject's own
   * protected names changes no text, so running it would be work with no effect.
   *
   * @return {@code true} when it holds at least one third-party name
   */
  public boolean isActive() {
    return terms.stream().anyMatch(Term::replaced);
  }
}
