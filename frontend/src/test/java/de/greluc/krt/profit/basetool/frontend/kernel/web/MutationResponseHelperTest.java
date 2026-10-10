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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Unit tests for {@link MutationResponseHelper}, pinning the exact Post/Redirect/Get behaviour it
 * replaces: success flashes {@code successToast}, any failure (a {@link BackendServiceException} or
 * any other exception) flashes {@code errorToast}, and every path redirects to the given view.
 */
class MutationResponseHelperTest {

  private final MutationResponseHelper helper = new MutationResponseHelper();

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
