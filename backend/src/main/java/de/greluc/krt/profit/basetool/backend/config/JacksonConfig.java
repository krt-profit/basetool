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

package de.greluc.krt.profit.basetool.backend.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.module.SimpleModule;

/**
 * Applies the project's two REST-layer Jackson defaults to Spring Boot's auto-configured Jackson 3
 * {@code JsonMapper}:
 *
 * <ol>
 *   <li>registers {@link NormalizedStringDeserializer} so every JSON {@code String} read by the
 *       REST layer is trimmed, NFC-normalized and length-capped — the same normalization {@link
 *       GlobalBindingAdvice} performs for x-www-form-urlencoded posts, so a JSON body and a form
 *       body cannot produce subtly different stored values;
 *   <li>restores {@code FAIL_ON_NULL_FOR_PRIMITIVES = false}. Jackson 3 flipped this feature's
 *       default to {@code true}; Jackson 2 (which the project's DTOs were written against) mapped
 *       an absent/null JSON value for a primitive field to its type default. Without this, a
 *       request body that omits a nested primitive — e.g. {@code location.hidden} (a {@code
 *       boolean} on {@code LocationDto}) in a RefineryOrder create payload — would start returning
 *       400 instead of defaulting to {@code false}.
 * </ol>
 *
 * <p><b>Why a customizer and not a {@code @Primary ObjectMapper} bean.</b> Spring Boot 4 /
 * Framework 7 drive the REST message converters with their own Jackson 3 {@code JsonMapper}. A
 * hand-rolled {@code @Primary} mapper — especially a Jackson 2 one — is not the instance Spring
 * actually uses, so configuring it would silently leave the REST layer on the framework's bare
 * defaults. {@link JsonMapperBuilderCustomizer} is the supported hook: Spring Boot applies it to
 * the very builder it constructs the primary {@code JsonMapper} from.
 */
@Configuration
public class JacksonConfig {

  /**
   * Applies the normalized-string module and the {@code FAIL_ON_NULL_FOR_PRIMITIVES = false}
   * compatibility setting to the builder Spring Boot uses to construct its primary Jackson 3 {@code
   * JsonMapper}, so both govern every payload the REST layer parses.
   *
   * @return a customizer that registers {@link NormalizedStringDeserializer} and restores the
   *     Jackson 2 primitive-null leniency on the primary mapper
   */
  @Bean
  public JsonMapperBuilderCustomizer appJsonMapperBuilderCustomizer() {
    return builder ->
        builder
            .addModule(normalizedStringModule())
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
  }

  /**
   * Builds the Jackson 3 module that installs {@link NormalizedStringDeserializer} for every JSON
   * {@code String} field.
   *
   * @return a module wiring {@link NormalizedStringDeserializer} as the {@code String} deserializer
   */
  @NotNull
  private static SimpleModule normalizedStringModule() {
    SimpleModule module = new SimpleModule();
    module.addDeserializer(String.class, new NormalizedStringDeserializer());
    return module;
  }
}
