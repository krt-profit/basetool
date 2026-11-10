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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Pins mutation feedback while session loss propagates without a misleading failure toast. */
class MutationResponseHelperTest {

  private final MutationResponseHelper helper = new MutationResponseHelper();

  @Test
  void sessionLossEscapesBeforeAnyFlashAttributeIsPublished() {
    for (boolean wrapped : new boolean[] {false, true}) {
      ReauthenticationRequiredException reauth = expiredSession();
      RuntimeException failure =
          wrapped ? new CompletionException(new IllegalStateException(reauth)) : reauth;
      RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

      assertThatThrownBy(() ->
              helper.mutate(
                  attributes,
                  "/orders",
                  "success",
                  "failure",
                  () -> {
                    throw failure;
                  }))
          .isSameAs(reauth);
      assertThat(attributes.getFlashAttributes()).isEmpty();
    }
  }

  @Test
  void onSuccess_flashesSuccessToastAndRedirects() {
    RedirectAttributesModelMap ra = new RedirectAttributesModelMap();

    String view =
        helper.mutate(ra, "/admin/materials", "notification.success.save", "error.x", () -> {});

    assertThat(view).isEqualTo("redirect:/admin/materials");
    assertThat(ra.getFlashAttributes()).containsKey("successToast").doesNotContainKey("errorToast");
    assertThat(ra.getFlashAttributes().get("successToast")).isEqualTo("notification.success.save");
  }

  @Test
  void onBackendServiceException_flashesErrorToastAndRedirects() {
    RedirectAttributesModelMap ra = new RedirectAttributesModelMap();

    String view =
        helper.mutate(
            ra,
            "/admin/materials",
            "notification.success.save",
            "error.admin.materials.save",
            () -> {
              throw new BackendServiceException("boom", null, 500);
            });

    assertThat(view).isEqualTo("redirect:/admin/materials");
    assertThat(ra.getFlashAttributes()).containsKey("errorToast").doesNotContainKey("successToast");
    assertThat(ra.getFlashAttributes().get("errorToast")).isEqualTo("error.admin.materials.save");
  }

  @Test
  void onUnexpectedException_flashesErrorToastAndRedirects() {
    RedirectAttributesModelMap ra = new RedirectAttributesModelMap();

    String view =
        helper.mutate(
            ra,
            "/orders",
            "success.joborder.delete",
            "error.joborder.delete.failed",
            () -> {
              throw new IllegalStateException("unexpected");
            });

    assertThat(view).isEqualTo("redirect:/orders");
    assertThat(ra.getFlashAttributes()).containsKey("errorToast").doesNotContainKey("successToast");
    assertThat(ra.getFlashAttributes().get("errorToast")).isEqualTo("error.joborder.delete.failed");
  }
}
