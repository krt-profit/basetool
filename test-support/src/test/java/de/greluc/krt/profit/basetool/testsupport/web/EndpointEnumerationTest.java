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
 * The enumeration both anonymous-surface sweeps stand on.
 *
 * <p><b>Why this file exists.</b> A sweep that enumerates nothing passes every assertion it makes.
 * Before #1804 this engine lived twice and was only ever exercised through its two callers — so a
 * bug in it would have turned both guards green at the same moment, on a surface whose whole point
 * is exhaustiveness. Each case below is one way that could happen.
 */
class EndpointEnumerationTest {

  /** The value the engine substitutes for an id-shaped variable. */
  private static final String NIL_UUID = "00000000-0000-4000-8000-000000000000";

  /** A placeholder mapping target; the enumeration reads the registry's keys, never its values. */
  void handlerMethod() {
    // Intentionally empty.
  }

  // ---------------------------------------------------------------- substituteVariables

  @Test
  @DisplayName("a path with no variables comes back untouched")
  void plainPathIsUnchanged() {
    assertThat(EndpointEnumeration.substituteVariables("/api/v1/missions"))
        .isEqualTo("/api/v1/missions");
  }

  @Test
  @DisplayName("an id-shaped variable becomes a nil UUID, anything else becomes x")
  void variablesAreSubstitutedByShape() {
    // The distinction is what keeps a substituted path routable: a UUID-typed @PathVariable rejects
    // "x" at the binder, which answers 400 — and a 400 is not the refusal these sweeps ask about,
    // so the path would read as "not served" for the wrong reason.
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
    // Substituting these would assert a path the application never routes, which is worse than not
    // asserting at all: the sweep would report a refusal it invented.
    assertThat(EndpointEnumeration.substituteVariables("/assets/**")).isNull();
    assertThat(EndpointEnumeration.substituteVariables("/**/*.map")).isNull();
    assertThat(EndpointEnumeration.substituteVariables("/x/{id:[0-9]+}")).isNull();
    assertThat(EndpointEnumeration.substituteVariables("/x/{unclosed")).isNull();
  }

  // ---------------------------------------------------------------- isUnder

  @Test
  @DisplayName("a subtree root covers itself and everything below it")
  void isUnderCoversTheRootAndItsChildren() {
    assertThat(EndpointEnumeration.isUnder("/internal", "/internal")).isTrue();
    assertThat(EndpointEnumeration.isUnder("/internal/users/exists", "/internal")).isTrue();
  }

  @Test
  @DisplayName("a neighbour that merely shares the opening characters is NOT under it")
  void isUnderDoesNotSwallowANeighbour() {
    // The 2026-09-06 defect, pinned. A `startsWith` comparison silently removed a neighbouring path
    // from a sweep whose entire value is that it covers everything — and it did so in both copies
    // of
    // this engine at once, which is why the engine is shared now and why this case lives here.
    assertThat(EndpointEnumeration.isUnder("/internal-facing", "/internal")).isFalse();
    assertThat(EndpointEnumeration.isUnder("/errors", "/error")).isFalse();
    assertThat(EndpointEnumeration.isUnder("/api/v1/missions", "/api/v1/mission")).isFalse();
  }

  @Test
  @DisplayName("an unrelated path is not under it either")
  void isUnderRejectsUnrelatedPaths() {
    assertThat(EndpointEnumeration.isUnder("/api/v1/missions", "/internal")).isFalse();
  }

  // ---------------------------------------------------------------- mappings

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
    // Rare but legal, and such a mapping answers EVERY verb — so the read is the one that leaks.
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
              // Distinct mappings — Spring would refuse to register the same one twice — that
              // collapse onto the same concrete path once the variables are substituted. Without
              // the de-duplication the sweep would issue this call twice and report it twice.
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

  // ---------------------------------------------------------------- fixtures

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
   * Builder options that produce a parsed {@code PathPattern} condition.
   *
   * <p>Spelled out rather than left to a default: the enumeration reads {@code
   * getPathPatternsCondition()}, and a {@link RequestMappingInfo} built by hand without a parser
   * carries none — so the fixture would hand it an empty pattern set and every assertion here would
   * pass against zero calls. That is the exact failure this class exists to prevent, and a fixture
   * is no better a place for it than the engine.
   *
   * @return options pinned to the {@link PathPatternParser}
   */
  private static RequestMappingInfo.BuilderConfiguration parsedPatterns() {
    RequestMappingInfo.BuilderConfiguration options = new RequestMappingInfo.BuilderConfiguration();
    options.setPatternParser(new PathPatternParser());
    return options;
  }
}
