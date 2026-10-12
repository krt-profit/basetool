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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Verifies that every {@code terms.*} key in the committed message bundle is reachable by the
 * numbering walk of {@link TermsDocumentService}, so no clause is silently dropped, and that the
 * load-bearing clauses of sections 4 and 12 keep their shape in both languages.
 */
class TermsDocumentStructureTest {

  /** German bundle — the authoritative source of the wording and of the version digest. */
  private static final Path BUNDLE = Path.of("src/main/resources/messages_de.properties");

  /** Key prefix scoping both sides of the comparison to the Terms of Use. */
  private static final String TERMS_PREFIX = "terms.";

  /** Any version; this test is about structure, not about the digest. */
  private static final String STUB_VERSION = "test-version";

  /** Zero-based index of section 4, the user obligations. */
  private static final int SECTION_OBLIGATIONS = 3;

  /** Zero-based index of section 12, the changes to the terms. */
  private static final int SECTION_AMENDMENTS = 11;

  /** Number of rules section 4 states for a connected application. */
  private static final int CONNECTED_APP_RULES = 5;

  /** The public list of approved clients the approval clause names. */
  private static final String APPROVED_CLIENTS_URL =
      "https://github.com/krt-profit/basetool/blob/main/docs/legal/approved-clients.md";

  /** The disclaimer of support for third-party software, which approving clients contradicts. */
  private static final String[] DROPPED_DISCLAIMERS = {
    "deren Einsatz nicht", "does not support its use"
  };

  /** The consent-by-access and consent-by-continued-use wordings that recorded consent replaced. */
  private static final String[] CONSENT_FICTIONS = {
    "fortgesetzte Nutzung",
    "Continued use",
    "Mit dem Zugriff auf die Plattform erkennt",
    "By accessing the Platform, the user accepts"
  };

  /**
   * Builds the service over the real bundle.
   *
   * @return the service under test
   */
  private static TermsDocumentService service() {
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
    TermsVersionProvider versionProvider = mock(TermsVersionProvider.class);
    when(versionProvider.getCurrentVersion()).thenReturn(STUB_VERSION);
    return new TermsDocumentService(messageSource, versionProvider);
  }

  /**
   * Reads every {@code terms.*} key committed in the German bundle.
   *
   * @return the keys, sorted
   * @throws IOException if the bundle cannot be read
   */
  private static Set<String> bundleKeys() throws IOException {
    Properties properties = new Properties();
    try (Reader reader =
        new InputStreamReader(Files.newInputStream(BUNDLE), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    Set<String> keys = new TreeSet<>();
    properties.stringPropertyNames().stream()
        .filter(key -> key.startsWith(TERMS_PREFIX))
        .forEach(keys::add);
    return keys;
  }

  /**
   * Collects every string the assembled document actually exposes.
   *
   * @param document the assembled document
   * @return the rendered texts
   */
  private static List<String> renderedTexts(TermsDocumentDto document) {
    List<String> texts = new ArrayList<>();
    texts.add(document.title());
    texts.add(document.intro());
    texts.add(document.lastUpdated());
    for (TermsSectionDto section : document.sections()) {
      texts.add(section.heading());
      for (TermsClauseDto clause : section.clauses()) {
        texts.add(clause.text());
        texts.addAll(clause.bullets());
      }
    }
    return texts;
  }

  /**
   * Every clause in the bundle reaches the document.
   *
   * @throws IOException if the bundle cannot be read
   */
  @Test
  @DisplayName("every terms.* clause in the bundle is reachable by the document walk")
  void everyClauseIsRendered() throws IOException {
    Properties properties = new Properties();
    try (Reader reader =
        new InputStreamReader(Files.newInputStream(BUNDLE), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    List<String> rendered = renderedTexts(service().document(Locale.GERMAN));

    Set<String> missing = new TreeSet<>();
    for (String key : bundleKeys()) {
      if (!rendered.contains(properties.getProperty(key))) {
        missing.add(key);
      }
    }

    assertThat(missing)
        .as(
            "terms.* keys present in the bundle but not reachable by TermsDocumentService — "
                + "a gap in the numbering truncates the walk and silently drops the rest")
        .isEmpty();
  }

  /**
   * The document exposes nothing the bundle does not declare.
   *
   * <p>The counterpart to the test above: a walk that invented a heading, or repeated one, would
   * put text in front of a member that no reviewer ever approved.
   *
   * @throws IOException if the bundle cannot be read
   */
  @Test
  @DisplayName("the document renders nothing the bundle does not declare")
  void nothingIsInvented() throws IOException {
    Properties properties = new Properties();
    try (Reader reader =
        new InputStreamReader(Files.newInputStream(BUNDLE), StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    Set<String> declared = new TreeSet<>();
    bundleKeys().forEach(key -> declared.add(properties.getProperty(key)));

    assertThat(renderedTexts(service().document(Locale.GERMAN))).allSatisfy(declared::contains);
  }

  /**
   * The English bundle produces the same shape as the German one.
   *
   * <p>A translation that dropped a clause would let an English-speaking member agree to a shorter
   * contract than a German-speaking one — the same wording has to be on offer in both.
   */
  @Test
  @DisplayName("the English document has the same structure as the German one")
  void translationsHaveTheSameShape() {
    TermsDocumentDto german = service().document(Locale.GERMAN);
    TermsDocumentDto english = service().document(Locale.ENGLISH);

    assertThat(english.sections()).hasSameSizeAs(german.sections());
    for (int i = 0; i < german.sections().size(); i++) {
      assertThat(english.sections().get(i).clauses())
          .as("clause count of section %d", i + 1)
          .hasSameSizeAs(german.sections().get(i).clauses());
      for (int j = 0; j < german.sections().get(i).clauses().size(); j++) {
        assertThat(english.sections().get(i).clauses().get(j).bullets())
            .as("bullet count of section %d, clause %d", i + 1, j + 1)
            .hasSameSizeAs(german.sections().get(i).clauses().get(j).bullets());
      }
    }
  }

  /**
   * The document carries the version an acceptance would be recorded against.
   *
   * <p>Without it a client would have to read the text from one response and the version from
   * another, and nothing would stop the two from referring to different wordings.
   */
  @Test
  @DisplayName("the document carries the version in force")
  void carriesTheVersion() {
    assertThat(service().document(Locale.GERMAN).version()).isEqualTo(STUB_VERSION);
  }

  /**
   * Section 4 links the public list of approved clients, no longer disclaims support for approved
   * software, and states in a paragraph of its own what a connected application may do
   * (REQ-SEC-027).
   *
   * @param language the language tag of the document
   */
  @ParameterizedTest
  @ValueSource(strings = {"de", "en"})
  @DisplayName("section 4 links the approved-client list and states what a connected app may do")
  void sectionFourCarriesTheExchangeTerms(String language) {
    TermsSectionDto section =
        service().document(Locale.forLanguageTag(language)).sections().get(SECTION_OBLIGATIONS);

    assertThat(section.clauses()).hasSize(2);
    assertThat(section.clauses().getFirst().bullets())
        .anySatisfy(bullet -> assertThat(bullet).contains(APPROVED_CLIENTS_URL))
        .noneSatisfy(bullet -> assertThat(bullet).containsAnyOf(DROPPED_DISCLAIMERS));
    assertThat(section.clauses().get(1).bullets()).hasSize(CONNECTED_APP_RULES);
  }

  /**
   * The intro and section 12 make the terms and their amendments depend on recorded consent instead
   * of treating access or continued use as consent (REQ-SEC-028).
   *
   * @param language the language tag of the document
   */
  @ParameterizedTest
  @ValueSource(strings = {"de", "en"})
  @DisplayName("neither the intro nor section 12 treats access or continued use as consent")
  void termsNeedRecordedConsent(String language) {
    TermsDocumentDto document = service().document(Locale.forLanguageTag(language));
    List<String> texts = new ArrayList<>();
    texts.add(document.intro());
    document.sections().get(SECTION_AMENDMENTS).clauses().stream()
        .map(TermsClauseDto::text)
        .forEach(texts::add);

    assertThat(texts).noneSatisfy(text -> assertThat(text).containsAnyOf(CONSENT_FICTIONS));
  }
}
