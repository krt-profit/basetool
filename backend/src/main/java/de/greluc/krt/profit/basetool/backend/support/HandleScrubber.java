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

import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
 * <p><b>Longest handle first.</b> Replacing "Val" before "Valkyrie" would leave the fragment
 * "kyrie" behind, which is both a leak and a corruption. Sorting by descending length makes the
 * longer match win.
 *
 * <p><b>Handles under {@value #MIN_HANDLE_LENGTH} characters are skipped.</b> A two-character
 * handle occurs as a substring of ordinary words constantly, and replacing it would shred every
 * note in the export. A short handle that genuinely appears is left to the human review, which is
 * the correct trade: a readable export with a reviewed residue beats a mangled one.
 */
public final class HandleScrubber {

  /** Shortest handle that is safe to replace by substring match. */
  public static final int MIN_HANDLE_LENGTH = 3;

  /** What a removed third-party handle is replaced with. */
  public static final String REPLACEMENT = "[anderes Mitglied]";

  private final List<String> handles;

  /**
   * Creates a scrubber for the given third-party handles.
   *
   * @param otherHandles every other member's effective name; the requester's own must already be
   *     excluded by the caller, because replacing it would remove the one name the export is
   *     supposed to be about
   */
  public HandleScrubber(@NotNull Collection<String> otherHandles) {
    this.handles =
        otherHandles.stream()
            .filter(h -> h != null && h.trim().length() >= MIN_HANDLE_LENGTH)
            .map(String::trim)
            .distinct()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toList();
  }

  /**
   * Replaces every occurrence of a known third-party handle in the text, case-insensitively.
   *
   * <p>Case-insensitive because whoever wrote the note was typing, not copying from a roster — the
   * same reason the Personensuche matches that way (REQ-SEC-060).
   *
   * @param text the free text, possibly {@code null}
   * @return the text with third-party handles replaced, or {@code null} when the input was
   */
  public @Nullable String scrub(@Nullable String text) {
    if (text == null || text.isEmpty() || handles.isEmpty()) {
      return text;
    }
    String result = text;
    for (String handle : handles) {
      result = replaceIgnoreCase(result, handle);
    }
    return result;
  }

  /**
   * Replaces every case-insensitive occurrence of a literal needle.
   *
   * <p>Hand-rolled rather than {@code Pattern.compile(quote(h), CASE_INSENSITIVE)} because this
   * runs once per handle per free-text value — a few hundred handles across a few thousand values —
   * and compiling a pattern per pair is the one part of an export that could plausibly become slow.
   *
   * @param text the text to scan
   * @param needle the literal to replace
   * @return the text with every occurrence replaced
   */
  private static @NotNull String replaceIgnoreCase(@NotNull String text, @NotNull String needle) {
    String lowerText = text.toLowerCase(Locale.ROOT);
    String lowerNeedle = needle.toLowerCase(Locale.ROOT);
    int at = lowerText.indexOf(lowerNeedle);
    if (at < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    int from = 0;
    while (at >= 0) {
      out.append(text, from, at).append(REPLACEMENT);
      from = at + needle.length();
      at = lowerText.indexOf(lowerNeedle, from);
    }
    return out.append(text, from, text.length()).toString();
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
