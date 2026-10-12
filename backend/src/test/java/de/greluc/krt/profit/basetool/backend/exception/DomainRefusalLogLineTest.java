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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.joborder.api.ProductionAllocationException;
import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.platform.api.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins that a domain refusal's WARN line carries exactly its allow-listed fields and nothing of the
 * request body, and that the prod JSON encoder writes them as top-level fields (REQ-OBS-004).
 */
class DomainRefusalLogLineTest {

  private static final UUID MATERIAL_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

  private static final UUID ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");

  private static final Set<String> LINE_FIELDS =
      Set.of(
          "@timestamp",
          "@version",
          "message",
          "logger_name",
          "thread_name",
          "level",
          "level_value",
          "reason",
          "materialId",
          "inventoryItemId",
          "consumed",
          "slice",
          "thrownAt");

  private final Logger handlerLogger =
      (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private GlobalExceptionHandler handler;

  private HttpServletRequest request;

  @BeforeEach
  void setUp() {
    AppProblemProperties problemProperties =
        new AppProblemProperties("https://profit-base.online/problems/");
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    handler =
        new GlobalExceptionHandler(
            problemProperties,
            new ProblemResponseFactory(problemProperties),
            messageSource,
            new SimpleMeterRegistry());
    request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/orders/x/items/y/production");
    when(request.getMethod()).thenReturn("POST");
    appender.start();
    handlerLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    handlerLogger.detachAppender(appender);
    appender.stop();
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void theWarnLineNamesTheReasonAndItsFieldsInThePlainLayout() {
    handler.handleAppException(
        ProductionAllocationException.overOrderSlice(MATERIAL_ID, ENTRY_ID, 40.0, 10.0), request);

    assertThat(appender.list).hasSize(1);
    ILoggingEvent event = appender.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains("Production allocation for POST", "code=PRODUCTION_ALLOCATION")
        .contains("reason=OVER_ORDER_SLICE")
        .contains("materialId=" + MATERIAL_ID)
        .contains("inventoryItemId=" + ENTRY_ID)
        .contains("consumed=40.0")
        .contains("slice=10.0");
  }

  @Test
  void theProdJsonLineCarriesExactlyTheAllowListedFieldsAndNoBody() {
    handler.handleAppException(
        ProductionAllocationException.overOrderSlice(MATERIAL_ID, ENTRY_ID, 40.0, 10.0), request);

    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(handlerLogger.getLoggerContext());
    encoder.start();
    String json = new String(encoder.encode(appender.list.getFirst()), StandardCharsets.UTF_8);
    JsonNode line = JsonMapper.builder().build().readTree(json);

    assertThat(line.get("reason").asString()).isEqualTo("OVER_ORDER_SLICE");
    assertThat(line.get("materialId").asString()).isEqualTo(MATERIAL_ID.toString());
    assertThat(line.get("inventoryItemId").asString()).isEqualTo(ENTRY_ID.toString());
    assertThat(line.get("consumed").asDouble()).isEqualTo(40.0);
    assertThat(line.get("slice").asDouble()).isEqualTo(10.0);
    assertThat(line.propertyNames()).isSubsetOf(LINE_FIELDS);
  }
}
