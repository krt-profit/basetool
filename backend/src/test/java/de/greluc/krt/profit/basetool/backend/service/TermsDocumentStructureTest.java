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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.TermsClauseDto;
import de.greluc.krt.profit.basetool.backend.model.dto.TermsDocumentDto;
import de.greluc.krt.profit.basetool.backend.model.dto.TermsSectionDto;
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
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Pins the Terms-of-Use document against its message bundle.
 *
 * <p>This carries what the frontend's {@code TermsTemplateBundleParityTest} used to, and for the
 * same reason: the terms are a contract text, so a clause that exists as a {@code terms.*} key but
 * is never shown to anybody never becomes part of the agreement. What changed is where it can go
 * missing. There is no longer a template listing every key — {@link TermsDocumentService} walks the
 * numbering and stops at the first gap — so the failure mode moved from "a key nobody referenced"
 * to "a key the walk cannot reach". Both drop a clause from a legal text with no other symptom.
 *
 * <p>A mis-numbered key is the concrete danger. Insert {@code terms.p_4_7} while {@code
 * terms.p_4_6} is absent and the walk stops at 5: the clause is in the repository, in the version
 * digest, and invisible to every reader. This test fails on exactly that.
 *
 * <p>Reads the committed bundle under {@code src/main/resources} directly rather than the classpath
 * copy, matching the frontend's bundle tests; the Gradle {@code Test} task runs with the module
 * directory as its working directory.
 */
class TermsDocumentStructureTest {

  /** German bundle — the authoritative source of the wording and of the version digest. */
  private static final Path BUNDLE = Path.of("src/main/resources/messages_de.properties");

  /** Key prefix scoping both sides of the comparison to the Terms of Use. */
  private static final String TERMS_PREFIX = "terms.";

  /** Any version; this test is about structure, not about the digest. */
  private static final String STUB_VERSION = "test-version";

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
}
