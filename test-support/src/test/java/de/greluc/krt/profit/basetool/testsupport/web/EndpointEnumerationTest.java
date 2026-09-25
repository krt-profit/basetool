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

package de.greluc.krt.profit.basetool.testsupport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Unit tests for the endpoint enumeration both anonymous-surface sweeps rely on, since a sweep that
 * enumerates nothing passes every assertion.
 */
class EndpointEnumerationTest {

  /** The value the engine substitutes for an id-shaped variable. */
  private static final String NIL_UUID = "00000000-0000-4000-8000-000000000000";

  /** A placeholder mapping target; the enumeration reads the registry's keys, never its values. */
  void handlerMethod() {}

  @Test
  @DisplayName("a path with no variables comes back untouched")
  void plainPathIsUnchanged() {
    assertThat(EndpointEnumeration.substituteVariables("/api/v1/missions"))
        .isEqualTo("/api/v1/missions");
  }

  @Test
  @DisplayName("an id-shaped variable becomes a nil UUID, anything else becomes x")
  void variablesAreSubstitutedByShape() {
    assertThat(EndpointEnumeration.substituteVariables("/api/v1/missions/{id}"))
        .isEqualTo("/api/v1/missions/" + NIL_UUID);
    assertThat(EndpointEnumeration.substituteVariables("/api/v1/missions/{missionId}/participants"))
        .isEqualTo("/api/v1/missions/" + NIL_UUID + "/participants");
    assertThat(EndpointEnumeration.substituteVariables("/x/{uuid}")).isEqualTo("/x/" + NIL_UUID);
    assertThat(EndpointEnumeration.substituteVariables("/api/v1/materials/{name}"))
        .isEqualTo("/api/v1/materials/x");
  }

  @Test
  @DisplayName("the id test reads the whole variable name, case-insensitively")
  void idDetectionIsCaseInsensitiveAndSuffixBased() {
    assertThat(EndpointEnumeration.substituteVariables("/x/{orgUnitID}"))
        .as("Spring does not care about the case a developer wrote the variable in")
        .isEqualTo("/x/" + NIL_UUID);
    assertThat(EndpointEnumeration.substituteVariables("/x/{identifier}"))
        .as("`identifier` merely begins with `id` — it is not an id-shaped name")
        .isEqualTo("/x/x");
  }

  @Test
  @DisplayName("a pattern with no single concrete spelling is dropped rather than guessed")
  void unroutablePatternsAreDropped() {
    assertThat(EndpointEnumeration.substituteVariables("/assets/**")).isNull();
    assertThat(EndpointEnumeration.substituteVariables("/**/*.map")).isNull();
    assertThat(EndpointEnumeration.substituteVariables("/x/{id:[0-9]+}")).isNull();
    assertThat(EndpointEnumeration.substituteVariables("/x/{unclosed")).isNull();
  }

  @Test
  @DisplayName("a subtree root covers itself and everything below it")
  void isUnderCoversTheRootAndItsChildren() {
    assertThat(EndpointEnumeration.isUnder("/internal", "/internal")).isTrue();
    assertThat(EndpointEnumeration.isUnder("/internal/users/exists", "/internal")).isTrue();
  }

  @Test
  @DisplayName("a neighbour that merely shares the opening characters is NOT under it")
  void isUnderDoesNotSwallowANeighbour() {
    assertThat(EndpointEnumeration.isUnder("/internal-facing", "/internal")).isFalse();
    assertThat(EndpointEnumeration.isUnder("/errors", "/error")).isFalse();
    assertThat(EndpointEnumeration.isUnder("/api/v1/missions", "/api/v1/mission")).isFalse();
  }

  @Test
  @DisplayName("an unrelated path is not under it either")
  void isUnderRejectsUnrelatedPaths() {
    assertThat(EndpointEnumeration.isUnder("/api/v1/missions", "/internal")).isFalse();
  }

  @Test
  @DisplayName("every declared verb of a mapping becomes its own call")
  void everyVerbIsExpanded() throws Exception {
    StaticWebApplicationContext context =
        registryWith(
            register ->
                register.accept(
                    RequestMappingInfo.paths("/api/v1/missions/{id}")
                        .methods(RequestMethod.GET, RequestMethod.DELETE)
                        .options(parsedPatterns())
                        .build()));

    assertThat(EndpointEnumeration.mappings(context))
        .containsExactly(
            new Call(HttpMethod.DELETE, "/api/v1/missions/" + NIL_UUID),
            new Call(HttpMethod.GET, "/api/v1/missions/" + NIL_UUID));
  }

  @Test
  @DisplayName("a mapping that declares no verb is swept as GET")
  void verblessMappingBecomesGet() throws Exception {
    StaticWebApplicationContext context =
        registryWith(
            register ->
                register.accept(
                    RequestMappingInfo.paths("/error").options(parsedPatterns()).build()));

    assertThat(EndpointEnumeration.mappings(context))
        .containsExactly(new Call(HttpMethod.GET, "/error"));
  }

  @Test
  @DisplayName("two mappings that substitute to one path are swept once, and the order is stable")
  void resultIsDeduplicatedAndStablySorted() throws Exception {
    StaticWebApplicationContext context =
        registryWith(
            register -> {
              register.accept(
                  RequestMappingInfo.paths("/zebra")
                      .methods(RequestMethod.GET)
                      .options(parsedPatterns())
                      .build());
              register.accept(
                  RequestMappingInfo.paths("/alpha/{id}")
                      .methods(RequestMethod.POST)
                      .options(parsedPatterns())
                      .build());
              register.accept(
                  RequestMappingInfo.paths("/alpha/{missionId}")
                      .methods(RequestMethod.POST)
                      .options(parsedPatterns())
                      .build());
            });

    assertThat(EndpointEnumeration.mappings(context))
        .as(
            "sorted by the rendered `VERB /path` — so VERB FIRST, which is why GET /zebra precedes"
                + " POST /alpha. Not alphabetical by path, and pinned here because it is the kind"
                + " of thing a later `sort by path` refactor would change without anyone noticing"
                + " that two failure lists stopped being comparable")
        .containsExactly(
            new Call(HttpMethod.GET, "/zebra"), new Call(HttpMethod.POST, "/alpha/" + NIL_UUID));
  }

  @Test
  @DisplayName("a pattern that cannot be made concrete contributes nothing")
  void unroutableMappingsAreNotSwept() throws Exception {
    StaticWebApplicationContext context =
        registryWith(
            register -> {
              register.accept(
                  RequestMappingInfo.paths("/assets/**")
                      .methods(RequestMethod.GET)
                      .options(parsedPatterns())
                      .build());
              register.accept(
                  RequestMappingInfo.paths("/ok")
                      .methods(RequestMethod.GET)
                      .options(parsedPatterns())
                      .build());
            });

    assertThat(EndpointEnumeration.mappings(context))
        .containsExactly(new Call(HttpMethod.GET, "/ok"));
  }

  @Test
  @DisplayName("the registry is looked up by name, because Actuator contributes a second one")
  void theMappingBeanIsResolvedByName() {
    StaticWebApplicationContext context = new StaticWebApplicationContext();
    context.refresh();

    assertThatThrownBy(() -> EndpointEnumeration.mappings(context))
        .as("by type alone the lookup is ambiguous in both applications; the name is the contract")
        .isInstanceOf(NoSuchBeanDefinitionException.class)
        .hasMessageContaining("requestMappingHandlerMapping");
  }

  /**
   * Builds a context holding one {@code requestMappingHandlerMapping} with the given mappings.
   *
   * @param mappings receives a sink that registers one {@link RequestMappingInfo} at a time
   * @return the refreshed context, ready to hand to {@link EndpointEnumeration#mappings}
   * @throws Exception when the placeholder handler method cannot be reflected
   */
  private StaticWebApplicationContext registryWith(Consumer<Consumer<RequestMappingInfo>> mappings)
      throws Exception {
    StaticWebApplicationContext context = new StaticWebApplicationContext();
    context.refresh();
    RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
    handlerMapping.setApplicationContext(context);
    handlerMapping.afterPropertiesSet();
    Method target = EndpointEnumerationTest.class.getDeclaredMethod("handlerMethod");
    mappings.accept(info -> handlerMapping.registerMapping(info, this, target));
    context.getBeanFactory().registerSingleton("requestMappingHandlerMapping", handlerMapping);
    return context;
  }

  /**
   * Builder options that give a hand-built {@link RequestMappingInfo} a parsed path-pattern
   * condition, without which the enumeration would see no patterns.
   *
   * @return options pinned to the {@link PathPatternParser}
   */
  private static RequestMappingInfo.BuilderConfiguration parsedPatterns() {
    RequestMappingInfo.BuilderConfiguration options = new RequestMappingInfo.BuilderConfiguration();
    options.setPatternParser(new PathPatternParser());
    return options;
  }
}
