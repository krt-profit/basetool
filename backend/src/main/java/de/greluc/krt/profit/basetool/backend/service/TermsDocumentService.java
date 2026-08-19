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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.dto.TermsClauseDto;
import de.greluc.krt.profit.basetool.backend.model.dto.TermsDocumentDto;
import de.greluc.krt.profit.basetool.backend.model.dto.TermsSectionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * Assembles the Terms-of-Use document from the message bundle (REQ-SEC-028).
 *
 * <p><strong>The key convention is the structure.</strong> There is no separate schema listing the
 * sections; {@code terms.h1_4} <em>is</em> the declaration that a fourth section exists, and {@code
 * terms.p_4_1} that it opens with a paragraph. Walking the keys means a clause added to the bundle
 * appears in both clients with no code change — which is the property that makes a single source
 * worth having. The convention is documented at the top of the {@code terms.*} block in {@code
 * messages_de.properties}.
 *
 * <p><strong>Walked until a gap, not counted.</strong> Numbering is dense by construction (a
 * document has no section 5 without a section 4), so the walk stops at the first missing key rather
 * than reading a count from somewhere that could disagree with the bundle. A gap therefore
 * truncates the document silently — which is why {@code TermsDocumentStructureTest} asserts that
 * every {@code terms.*} key in the bundle is reachable by this walk, so a mis-numbered key fails
 * the build instead of quietly dropping a clause from a legal text.
 *
 * <p>Nothing is cached. The bundle is already an in-memory resource, Spring's {@code MessageSource}
 * does its own caching, and the document is served rarely — a cache here would only add a way for
 * the served text to lag the deployed text.
 */
@Service
@RequiredArgsConstructor
public class TermsDocumentService {

  /** Key of the document title. */
  static final String KEY_TITLE = "terms.title";

  /** Key of the lead paragraph. */
  static final String KEY_INTRO = "terms.intro";

  /** Key of the "Stand ..." line. */
  static final String KEY_LAST_UPDATED = "terms.last_updated";

  private final MessageSource messageSource;
  private final TermsVersionProvider termsVersionProvider;

  /**
   * Builds the document in the requested language.
   *
   * @param locale the language to render; falls back to the bundle's default when unsupported
   * @return the document, carrying the version an acceptance would be recorded against
   */
  public @NotNull TermsDocumentDto document(@NotNull Locale locale) {
    List<TermsSectionDto> sections = new ArrayList<>();
    for (int section = 1; ; section++) {
      String heading = optional("terms.h1_" + section, locale);
      if (heading == null) {
        break;
      }
      sections.add(new TermsSectionDto(heading, clauses(section, locale)));
    }
    return new TermsDocumentDto(
        termsVersionProvider.getCurrentVersion(),
        required(KEY_TITLE, locale),
        required(KEY_INTRO, locale),
        List.copyOf(sections),
        required(KEY_LAST_UPDATED, locale));
  }

  /**
   * Collects the paragraphs of one section, each with its bullets.
   *
   * @param section the section number
   * @param locale the language to render
   * @return the paragraphs in document order; empty when the section has none
   */
  private @NotNull List<TermsClauseDto> clauses(int section, @NotNull Locale locale) {
    List<TermsClauseDto> clauses = new ArrayList<>();
    for (int paragraph = 1; ; paragraph++) {
      String text = optional("terms.p_" + section + "_" + paragraph, locale);
      if (text == null) {
        break;
      }
      clauses.add(new TermsClauseDto(text, bullets(section, paragraph, locale)));
    }
    return List.copyOf(clauses);
  }

  /**
   * Collects the bullets under one paragraph.
   *
   * @param section the section number
   * @param paragraph the paragraph number within the section
   * @param locale the language to render
   * @return the bullets in order; empty when the paragraph has none
   */
  private @NotNull List<String> bullets(int section, int paragraph, @NotNull Locale locale) {
    List<String> bullets = new ArrayList<>();
    for (int item = 1; ; item++) {
      String bullet = optional("terms.list_" + section + "_" + paragraph + "_" + item, locale);
      if (bullet == null) {
        break;
      }
      bullets.add(bullet);
    }
    return List.copyOf(bullets);
  }

  /**
   * Reads a key that the document cannot be served without.
   *
   * @param key the message key
   * @param locale the language to render
   * @return the resolved text
   * @throws IllegalStateException if the key is absent — a Terms page with no title or no "Stand"
   *     line is not a document worth serving, and failing loudly beats emitting a hole in a legal
   *     text
   */
  private @NotNull String required(@NotNull String key, @NotNull Locale locale) {
    String value = optional(key, locale);
    if (value == null) {
      throw new IllegalStateException("Terms-of-Use bundle is missing the mandatory key " + key);
    }
    return value;
  }

  /**
   * Reads a key that may legitimately be absent, which is how the walk finds its end.
   *
   * @param key the message key
   * @param locale the language to render
   * @return the resolved text, or {@code null} when the key is not in the bundle
   */
  private @Nullable String optional(@NotNull String key, @NotNull Locale locale) {
    return messageSource.getMessage(key, null, null, locale);
  }
}
