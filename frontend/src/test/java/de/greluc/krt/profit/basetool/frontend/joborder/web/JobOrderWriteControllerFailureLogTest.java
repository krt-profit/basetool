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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.CreateJobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderForm;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.web.MutationResponseHelper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Pins that a failed material-order creation logs once: a backend refusal at {@code DEBUG}, since
 * the backend client already logged it, and an unexpected failure at {@code ERROR}, with the same
 * flash and redirect either way (observability spec, relayed backend failures).
 */
class JobOrderWriteControllerFailureLogTest {

  private static final int CONFLICT = 409;

  private final JobOrderBackendClient client = mock(JobOrderBackendClient.class);

  private final JobOrderWriteController controller =
      new JobOrderWriteController(
          client,
          mock(MutationResponseHelper.class),
          mock(LiveSyncLocalBus.class),
          mock(JobOrderCreation.class));

  private final Logger log = (Logger) LoggerFactory.getLogger(JobOrderWriteController.class);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private Level previousLevel;

  @BeforeEach
  void attach() {
    appender.start();
    log.addAppender(appender);
    previousLevel = log.getLevel();
    log.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detach() {
    log.detachAppender(appender);
    log.setLevel(previousLevel);
  }

  @Test
  void aBackendRefusalLogsOnceAtDebugAndReturnsToTheForm() {
    when(client.createOrder(any(CreateJobOrderDto.class)))
        .thenThrow(new BackendServiceException("conflict", null, CONFLICT));
    JobOrderForm form = materialForm();
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    String target = controller.createOrder(form, flash);

    assertThat(appender.list)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
          assertThat(event.getFormattedMessage())
              .isEqualTo("Failed to create job order (status 409)");
        });
    assertReturnedToTheForm(target, flash, form);
  }

  @Test
  void anUnexpectedFailureLogsOnceAtErrorAndReturnsToTheForm() {
    when(client.createOrder(any(CreateJobOrderDto.class)))
        .thenThrow(new IllegalStateException("broken"));
    JobOrderForm form = materialForm();
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    String target = controller.createOrder(form, flash);

    assertThat(appender.list)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.ERROR);
          assertThat(event.getFormattedMessage()).isEqualTo("Failed to create job order");
        });
    assertReturnedToTheForm(target, flash, form);
  }

  private static JobOrderForm materialForm() {
    JobOrderForm form = new JobOrderForm();
    form.setSource("list");
    form.getMaterials().getFirst().setMaterialId(UUID.randomUUID());
    form.getMaterials().getFirst().setAmount(2.0);
    return form;
  }

  private static void assertReturnedToTheForm(
      String target, RedirectAttributesModelMap flash, JobOrderForm form) {
    assertThat(target).isEqualTo("redirect:/orders/create?source=list");
    assertThat(flash.getFlashAttributes().get("errorToast"))
        .isEqualTo("error.joborder.create.failed");
    assertThat(flash.getFlashAttributes().get("jobOrderForm")).isSameAs(form);
  }
}
