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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Tests which extension origins the exchange accepts and the client each is bound to (REQ-XCH-027).
 */
class ExchangeCorsPropertiesTest {

  private static final String CHROME = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";
  private static final String OTHER = "chrome-extension://ponmlkjihgfedcbaponmlkjihgfedcba";
  private static final String FIREFOX = "moz-extension://0b5c4a52-7f5e-4f0a-9d3e-2c1b0a9f8e7d";

  private static final ExchangeCorsProperties BOUND =
      new ExchangeCorsProperties(
          List.of("hangar-exporter=" + CHROME, "other-extension=" + OTHER),
          List.of("hangar-exporter"));

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void initValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeFactory() {
    if (factory != null) {
      factory.close();
    }
  }

  @Test
  void boundOriginsFirefoxClientsAndBlankEntriesPass() {
    assertThat(
            validator.validate(
                new ExchangeCorsProperties(
                    List.of("hangar-exporter=" + CHROME, " "), List.of("hangar-exporter", ""))))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chrome-extension://abcdefghijklmnopabcdefghijklmnop",
        "hangar-exporter=chrome-extension://*",
        "hangar-exporter=chrome-extension://abcdefghijklmnopabcdefghijklmnoq",
        "hangar-exporter=chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
        "hangar-exporter=moz-extension://*",
        "hangar-exporter=https://profit-base.online",
        "Hangar=chrome-extension://abcdefghijklmnopabcdefghijklmnop",
        "*"
      })
  void anythingButOneClientBoundChromiumOriginIsRefused(String entry) {
    assertThat(validator.validate(new ExchangeCorsProperties(List.of(entry), List.of())))
        .singleElement()
        .satisfies(v -> assertThat(v.getPropertyPath().toString()).startsWith("extensionOrigins"));
  }

  @Test
  void oneOriginCannotBeBoundToTwoClients() {
    assertThat(
            validator.validate(
                new ExchangeCorsProperties(
                    List.of("hangar-exporter=" + CHROME, "other-extension=" + CHROME), List.of())))
        .singleElement()
        .satisfies(
            v -> assertThat(v.getPropertyPath().toString()).isEqualTo("eachOriginBoundOnce"));
  }

  @Test
  void aFirefoxClientMustBeAClientId() {
    assertThat(
            validator.validate(new ExchangeCorsProperties(List.of(), List.of("moz-extension://*"))))
        .singleElement()
        .satisfies(v -> assertThat(v.getPropertyPath().toString()).startsWith("firefoxClients"));
  }

  @Test
  void theCorsPatternsAreTheBoundOriginsAndFirefoxOnlyWhileAClientHasAFirefoxBuild() {
    assertThat(BOUND.allowedOriginPatterns())
        .containsExactly(CHROME, OTHER, ExchangeCorsProperties.FIREFOX_EXTENSION_ORIGINS);
    assertThat(
            new ExchangeCorsProperties(List.of("hangar-exporter=" + CHROME), List.of(" "))
                .allowedOriginPatterns())
        .containsExactly(CHROME);
    assertThat(BOUND.chromiumOrigins())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(CHROME, "hangar-exporter", OTHER, "other-extension"));
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "NULL",
      value = {
        "hangar-exporter, NULL, true",
        "versekit, NULL, true",
        "versekit, '', true",
        "hangar-exporter, " + CHROME + ", true",
        "other-extension, " + CHROME + ", false",
        "versekit, " + CHROME + ", false",
        "hangar-exporter, " + FIREFOX + ", true",
        "other-extension, " + FIREFOX + ", false",
        "hangar-exporter, https://evil.example, false",
        "hangar-exporter, https://profit-base.online, false",
        "hangar-exporter, null, false",
        "NULL, " + CHROME + ", false"
      })
  void anOriginIsAdmittedOnlyWithTheClientItIsBoundTo(
      String clientId, String origin, boolean expected) {
    assertThat(BOUND.admits(clientId, origin)).isEqualTo(expected);
  }

  @Test
  void commaSeparatedEnvironmentValuesBindEachEntry() {
    ExchangeCorsProperties bound =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "app.exchange.cors.extension-origins",
                        "hangar-exporter=" + CHROME + ",other-extension=" + OTHER,
                        "app.exchange.cors.firefox-clients",
                        "hangar-exporter")))
            .bindOrCreate("app.exchange.cors", ExchangeCorsProperties.class);

    assertThat(bound.allowedOriginPatterns())
        .containsExactly(CHROME, OTHER, ExchangeCorsProperties.FIREFOX_EXTENSION_ORIGINS);
    assertThat(bound.admits("other-extension", OTHER)).isTrue();
  }

  @Test
  void emptyEnvironmentValuesBindToNoExtension() {
    ExchangeCorsProperties bound =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "app.exchange.cors.extension-origins", "",
                        "app.exchange.cors.firefox-clients", "")))
            .bindOrCreate("app.exchange.cors", ExchangeCorsProperties.class);

    assertThat(bound.allowedOriginPatterns()).isEmpty();
    assertThat(bound.admits("versekit", null)).isTrue();
    assertThat(bound.admits("versekit", FIREFOX)).isFalse();
  }
}
