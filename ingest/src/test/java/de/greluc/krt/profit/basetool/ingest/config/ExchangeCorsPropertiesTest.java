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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Tests which extension origins the exchange CORS configuration accepts (REQ-XCH-027). */
class ExchangeCorsPropertiesTest {

  private static final String CHROME = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";

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
  void aChromiumExtensionOriginAndABlankEntryPass() {
    assertThat(validator.validate(new ExchangeCorsProperties(List.of(CHROME, " "), true)))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chrome-extension://*",
        "chrome-extension://abcdefghijklmnopabcdefghijklmnoq",
        "chrome-extension://abcdefghijklmnopabcdefghijklmno",
        "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
        "moz-extension://*",
        "https://profit-base.online",
        "*"
      })
  void anythingButOneChromiumExtensionOriginIsRefused(String origin) {
    assertThat(validator.validate(new ExchangeCorsProperties(List.of(origin), true)))
        .singleElement()
        .satisfies(v -> assertThat(v.getPropertyPath().toString()).startsWith("extensionOrigins"));
  }

  @Test
  void theFirefoxPatternFollowsTheConfiguredOriginsAndBlanksAreDropped() {
    assertThat(new ExchangeCorsProperties(List.of(CHROME, ""), true).allowedOriginPatterns())
        .containsExactly(CHROME, ExchangeCorsProperties.FIREFOX_EXTENSION_ORIGINS);
    assertThat(new ExchangeCorsProperties(List.of(), false).allowedOriginPatterns()).isEmpty();
  }

  @Test
  void anEmptyEnvironmentValueBindsToNoChromiumOrigin() {
    ExchangeCorsProperties bound =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of("app.exchange.cors.extension-origins", "")))
            .bindOrCreate("app.exchange.cors", ExchangeCorsProperties.class);

    assertThat(bound.allowedOriginPatterns())
        .containsExactly(ExchangeCorsProperties.FIREFOX_EXTENSION_ORIGINS);
  }

  @Test
  void aCommaSeparatedEnvironmentValueBindsEachOrigin() {
    String other = "chrome-extension://ponmlkjihgfedcbaponmlkjihgfedcba";
    ExchangeCorsProperties bound =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "app.exchange.cors.extension-origins",
                        CHROME + "," + other,
                        "app.exchange.cors.firefox-extensions",
                        "false")))
            .bindOrCreate("app.exchange.cors", ExchangeCorsProperties.class);

    assertThat(bound.allowedOriginPatterns()).containsExactly(CHROME, other);
  }
}
