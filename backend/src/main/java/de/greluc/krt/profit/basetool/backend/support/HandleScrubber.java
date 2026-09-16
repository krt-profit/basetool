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
import java.util.List;
import java.util.Map;
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
 * <p><b>Handles under {@value #MIN_HANDLE_LENGTH} characters are skipped.</b> A two-character
 * handle occurs as a substring of ordinary words constantly, and replacing it would shred every
 * note in the export. A short handle that genuinely appears is left to the human review, which is
 * the correct trade: a readable export with a reviewed residue beats a mangled one.
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

  /** Every handle to look for, longest first, so a longer match always wins over a shorter one. */
  private final List<String> handles;

  /**
   * The handles bucketed by their first character in both cases, so a position in the text is
   * tested only against the handles that could start there.
   *
   * <p>This is what keeps the pass affordable. The scan advances one position at a time, and a few
   * hundred handles tested at every character of a few thousand values would be the one part of an
   * export that could plausibly become slow -- the concern the previous implementation cited to
   * justify hand-rolling the match rather than compiling a pattern. A character that starts no
   * handle now costs one map lookup.
   */
  private final Map<Character, List<String>> byFirstChar;

  /**
   * Creates a scrubber for the given third-party handles.
   *
   * @param otherHandles every other member's name in every spelling the schema stores; the
   *     requester's own must already be excluded by the caller, because replacing it would remove
   *     the one name the export is supposed to be about
   */
  public HandleScrubber(@NotNull Collection<String> otherHandles) {
    this.handles =
        otherHandles.stream()
            .filter(h -> h != null && h.trim().length() >= MIN_HANDLE_LENGTH)
            .map(String::trim)
            .distinct()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toList();
    Map<Character, List<String>> buckets = new HashMap<>();
    for (String handle : this.handles) {
      char first = handle.charAt(0);
      // Both cases, because the scan looks up the text's character exactly as it stands there.
      char lower = Character.toLowerCase(first);
      char upper = Character.toUpperCase(first);
      buckets.computeIfAbsent(lower, k -> new ArrayList<>()).add(handle);
      if (upper != lower) {
        buckets.computeIfAbsent(upper, k -> new ArrayList<>()).add(handle);
      }
    }
    this.byFirstChar = Map.copyOf(buckets);
  }

  /**
   * Replaces every occurrence of a known third-party handle in the text, case-insensitively.
   *
   * <p>Case-insensitive because whoever wrote the note was typing, not copying from a roster -- the
   * same reason the Personensuche matches that way (REQ-SEC-060).
   *
   * @param text the free text, possibly {@code null}
   * @return the text with third-party handles replaced, or {@code null} when the input was
   */
  public @Nullable String scrub(@Nullable String text) {
    if (text == null || text.isEmpty() || handles.isEmpty()) {
      return text;
    }
    StringBuilder out = null;
    int copiedUpTo = 0;
    int at = 0;
    while (at < text.length()) {
      String hit = handleAt(text, at);
      if (hit == null) {
        at++;
        continue;
      }
      if (out == null) {
        out = new StringBuilder(text.length());
      }
      out.append(text, copiedUpTo, at).append(REPLACEMENT);
      at += hit.length();
      copiedUpTo = at;
    }
    if (out == null) {
      return text;
    }
    return out.append(text, copiedUpTo, text.length()).toString();
  }

  /**
   * The longest known handle that starts at this position, ignoring case.
   *
   * @param text the text being scanned
   * @param at the position to test
   * @return the matching handle as the dictionary spells it, or {@code null} when none starts here
   */
  private @Nullable String handleAt(@NotNull String text, int at) {
    List<String> candidates = byFirstChar.get(text.charAt(at));
    if (candidates == null) {
      return null;
    }
    for (String handle : candidates) {
      if (at + handle.length() <= text.length()
          && text.regionMatches(true, at, handle, 0, handle.length())) {
        return handle;
      }
    }
    return null;
  }

  /**
   * Whether this scrubber would change anything at all.
   *
   * @return {@code true} when it holds at least one usable handle
   */
  public boolean isActive() {
    return !handles.isEmpty();
  }
}
