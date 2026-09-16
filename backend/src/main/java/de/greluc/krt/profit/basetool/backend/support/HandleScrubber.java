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
 * Removes other members' handles from free text going into a data export (REQ-SEC-058).
 *
 * <p><b>This is the second line, not the first.</b> Third-party data is kept out of the export
 * primarily by the projections in {@link DataExportSections} never selecting another member's id or
 * handle column. That handles structured data completely. It cannot handle prose: a note the
 * requester wrote is <em>their</em> data and belongs in the export, and it may name somebody else
 * in the middle of a sentence, where no {@code SELECT} list can reach.
 *
 * <p><b>Its limit is stated rather than hidden.</b> It replaces the handles of members it is given.
 * It cannot recognise a person who has no account, or a nickname, or a misspelling — nothing can,
 * from text alone. That residue is why {@code docs/privacy/data-subject-requests.md} requires an
 * admin to <em>read</em> the free text before releasing an export, per Art. 15(4): "if a note names
 * another member, redact that name rather than withholding the whole entry". This class makes that
 * review short; it does not replace it.
 *
 * <p><b>One forward pass over the original text, longest match wins.</b> Both halves of that
 * sentence are load-bearing, and each of them replaced a defect.
 *
 * <p>Scanning left to right and consuming each match means the output is never re-read. The earlier
 * implementation looped over the handles, each pass scrubbing the previous pass's result, so a
 * three-character handle that happens to be a substring of the replacement text was substituted
 * <em>inside</em> a placeholder an earlier pass had written. {@code displayName} is self-service
 * with only a length limit on it, so any member could pick such a handle and corrupt the free text
 * in every other member's export. Nothing can recurse into text this pass has already emitted.
 *
 * <p>Longest first, because replacing "Val" before "Valkyrie" would leave the fragment "kyrie"
 * behind, which is both a leak and a corruption. The handles are sorted by descending length, and
 * the first one that matches at a position is the one taken.
 *
 * <p><b>Case-insensitivity through {@link String#regionMatches(boolean, int, String, int, int)},
 * never through a lower-cased copy.</b> {@code toLowerCase} is not length-preserving -- U+0130
 * (capital I with dot above) lowercases to two characters -- so an index found in a lower-cased
 * copy does not address the same character in the original. The earlier implementation spliced the
 * original at exactly those indices: a match after such a character leaked a prefix of the third
 * party's handle while the export still reported the removal as complete, and a match near the end
 * threw {@code IndexOutOfBoundsException} out of the export entirely. Any member could put that
 * character in their own note and then request their own export. {@code regionMatches} compares
 * character for character, so the two cannot drift apart.
 *
 * <p><b>A match must be flanked by non-alphanumeric characters.</b> Without that, a name is
 * replaced inside unrelated words: a third party called "Ore" turned "Store gefuellt" into
 * "St#OTHER_MEMBER# gefuellt". The boundary is what makes the three-character floor below
 * defensible — the class used to argue only the two-character case and then assert that three was
 * safe. A mention next to punctuation still matches, because punctuation is a boundary.
 *
 * <p><b>Handles under {@value #MIN_HANDLE_LENGTH} characters are skipped.</b> A two-character
 * handle occurs inside ordinary words constantly and no boundary rule saves it — "Al" is a word in
 * several languages. A short handle that genuinely appears is left to the human review, which is
 * the correct trade: a readable export with a reviewed residue beats a mangled one.
 *
 * <p><b>The subject's own names are matched and passed through verbatim.</b> They are not in the
 * replaced set — the export is about them — but they have to be in the <em>matcher</em>, or
 * longest-match cannot see them. With a third party called "Val" and nothing protecting the
 * subject's own "Valkyrie", "Notiz von Valkyrie" became "Notiz von #OTHER_MEMBER#kyrie": the
 * subject's own name shredded, in their own export, and reported to the reviewing admin as a
 * third-party redaction because the caller's flag keys off any change at all.
 */
public final class HandleScrubber {

  /** Shortest handle that is safe to replace by substring match. */
  public static final int MIN_HANDLE_LENGTH = 3;

  /**
   * What a removed third-party handle is replaced with.
   *
   * <p>A token and not prose, for the same reason {@link HandleAnonymisation#SENTINEL} is one: this
   * value is written into a document that has no language of its own. The JSON export is
   * machine-readable and carries no locale, and the PDF is rendered in the reader's -- so German
   * prose spliced into the text would be wrong in one of the two whichever language it was written
   * in, and the root {@code CLAUDE.md} rule admits no hardcoded user-visible text at all. The
   * surfaces that explain the token are localised instead: {@code pdf.export.note.thirdParty} names
   * it, and the JSON carries {@code thirdPartyHandlesRemoved} for a reader to key off.
   *
   * <p>Deliberately in the same {@code #WORD#} shape as the erasure sentinel, and deliberately not
   * the same token: this one means "a name that belongs to somebody else was removed here", the
   * other means "this person exercised their right to erasure". Collapsing them would lose that
   * distinction in the one place a reader has to be able to tell them apart.
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
   * The terms bucketed by their first character, so a position in the text is tested only against
   * the terms that could start there.
   *
   * <p>This is what keeps the pass affordable. The scan advances one position at a time, and a few
   * hundred terms tested at every character of a few thousand values would be the one part of an
   * export that could plausibly become slow — the concern the original hand-rolled match cited to
   * justify not compiling a pattern. A character that starts no term costs one map lookup.
   *
   * <p>Bucketed under every case-folding of the first character that {@link
   * String#regionMatches(boolean, int, String, int, int)} would accept, so the index cannot fold
   * more narrowly than the comparison does and silently miss a match.
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
   * @param otherHandles every other member's name in every spelling the schema stores; each
   *     occurrence is replaced by {@link #REPLACEMENT}
   * @param ownNames the subject's own names, in every spelling. Matched so that longest-match can
   *     see them, and then emitted <b>verbatim</b>: the export is about this person, and without
   *     them in the matcher a shorter third-party handle that is a prefix of the subject's own name
   *     shreds it.
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
   * Every character a case-insensitive comparison could accept in place of this one.
   *
   * <p>{@code regionMatches(true, …)} compares {@code toUpperCase} and then {@code toLowerCase} of
   * each pair, which accepts pairs that a single folding does not: {@code K} (U+212A) matches
   * {@code k}, {@code ı} matches {@code I}. Bucketing under only two foldings made the index the
   * narrower of the two, so a term starting with such a character was silently never tested.
   *
   * @param first the term's first character
   * @param <ignored> not used
   * @return the distinct characters to bucket under
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
   * Replaces every occurrence of a known third-party name in the text, case-insensitively.
   *
   * <p>Case-insensitive because whoever wrote the note was typing, not copying from a roster — the
   * same reason the Personensuche matches that way (REQ-SEC-060).
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
        // The subject's own name, in the casing the author typed. Emitting it rather than skipping
        // past it is what makes it a competitor in the longest-match above.
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
   * The longest known term that starts at this position, ignoring case, flanked by boundaries.
   *
   * @param text the text being scanned
   * @param at the position to test
   * @return the matching term, or {@code null} when none starts here
   */
  private @Nullable Term termAt(@NotNull String text, int at) {
    List<Term> candidates = byFirstChar.get(text.charAt(at));
    if (candidates == null) {
      return null;
    }
    for (Term term : candidates) {
      int end = at + term.text().length();
      if (end <= text.length()
          && text.regionMatches(true, at, term.text(), 0, term.text().length())
          && isBoundary(text, at - 1)
          && isBoundary(text, end)) {
        return term;
      }
    }
    return null;
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
