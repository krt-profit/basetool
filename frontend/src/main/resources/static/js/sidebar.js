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

document.addEventListener('DOMContentLoaded', function () {
    const hamburgerEl = document.getElementById('hamburger');
    if (hamburgerEl) {
        hamburgerEl.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            const sidebarEl = document.getElementById('sidebar');
            const overlayEl = document.getElementById('sidebar-overlay');
            if (sidebarEl) sidebarEl.classList.add('open');
            if (overlayEl) overlayEl.classList.add('visible');
        });
    }

    (function initSidebarSections() {
        const here = window.location.pathname;
        let activeLink = null;
        let activeLen = -1;
        document.querySelectorAll('.sidebar-links a').forEach(function (link) {
            const href = link.getAttribute('href');
            if (!href || href.charAt(0) !== '/') return;
            const linkPath = href.split('?')[0].split('#')[0];
            const isMatch =
                linkPath === '/'
                    ? here === '/'
                    : here === linkPath || here.indexOf(linkPath + '/') === 0;
            if (isMatch && linkPath.length > activeLen) {
                activeLink = link;
                activeLen = linkPath.length;
            }
        });
        let activeGroup = null;
        if (activeLink) {
            activeLink.classList.add('sidebar-link-active');
            activeLink.setAttribute('aria-current', 'page');
            activeGroup = activeLink.closest('.sidebar-group');
            if (activeGroup) activeGroup.classList.add('sidebar-group-active');
        }
        const readState = function (storageKey) {
            try {
                return localStorage.getItem(storageKey);
            } catch (_e) {
                return null;
            }
        };
        const writeState = function (storageKey, value) {
            try {
                localStorage.setItem(storageKey, value);
            } catch (_e) {}
        };
        document.querySelectorAll('.sidebar-group').forEach(function (group) {
            const groupKey = group.getAttribute('data-group-key');
            if (group === activeGroup) {
                group.open = true;
            } else if (groupKey) {
                const stored = readState('krt.sidebar.' + groupKey);
                if (stored === 'open') group.open = true;
                else if (stored === 'closed') group.open = false;
            }
            if (groupKey) {
                group.addEventListener('toggle', function () {
                    writeState('krt.sidebar.' + groupKey, group.open ? 'open' : 'closed');
                });
            }
        });
    })();

    if (window.location.pathname.indexOf('/admin/') === 0) {
        document.body.classList.add('admin-mode');
        const navEl = document.querySelector('header > nav');
        if (navEl && !document.querySelector('.admin-mode-chip')) {
            const labels = document.getElementById('sidebar');
            const chip = document.createElement('span');
            chip.className = 'admin-mode-chip';
            chip.setAttribute(
                'aria-label',
                (labels && labels.getAttribute('data-admin-mode-label')) || '',
            );
            chip.textContent = (labels && labels.getAttribute('data-admin-mode-chip')) || '';
            navEl.appendChild(chip);
        }
    }

    const footerEl = document.querySelector('body > .krt-footer');
    if (footerEl && footerEl !== document.body.lastElementChild) {
        document.body.appendChild(footerEl);
    }

    if (footerEl) {
        const phone = window.matchMedia('(max-width: 768px)');
        let covers = false;
        let pending = false;
        const measure = () => {
            pending = false;
            document.documentElement.style.setProperty(
                '--krt-footer-height',
                (covers ? footerEl.offsetHeight : 0) + 'px',
            );
        };
        const applyFooterHeight = () => {
            if (pending) return;
            pending = true;
            window.requestAnimationFrame(measure);
        };
        const onBreakpoint = () => {
            covers = window.getComputedStyle(footerEl).position === 'fixed';
            applyFooterHeight();
        };
        onBreakpoint();
        if (typeof phone.addEventListener === 'function') {
            phone.addEventListener('change', onBreakpoint);
        }
        window.addEventListener('resize', applyFooterHeight);
        if (typeof ResizeObserver !== 'undefined') {
            new ResizeObserver(applyFooterHeight).observe(footerEl);
        }
    }

    const sidebarEl = document.getElementById('sidebar');
    const overlayEl = document.getElementById('sidebar-overlay');
    const closeBtn = document.getElementById('close-sidebar');

    const closeSidebar = function (e) {
        if (e && e.currentTarget && e.currentTarget.tagName !== 'A') {
            e.preventDefault();
            e.stopPropagation();
        }
        if (sidebarEl) sidebarEl.classList.remove('open');
        if (overlayEl) overlayEl.classList.remove('visible');
    };

    if (closeBtn) closeBtn.addEventListener('click', closeSidebar);
    if (overlayEl) {
        overlayEl.addEventListener('click', closeSidebar);
        overlayEl.addEventListener('touchstart', closeSidebar, { passive: false });
    }

    document.querySelectorAll('.sidebar-links a, .sidebar-sublinks a').forEach((link) => {
        link.addEventListener('click', closeSidebar);
    });

    function formatUtcTimes(root) {
        (root || document).querySelectorAll('.utc-time').forEach((el) => {
            const text = el.textContent.trim();
            if (text && (text.endsWith('Z') || text.includes('T'))) {
                let dateStr = text;
                if (!dateStr.endsWith('Z')) dateStr += 'Z';
                const date = new Date(dateStr);
                if (!isNaN(date)) {
                    el.textContent = new Intl.DateTimeFormat(undefined, {
                        year: 'numeric',
                        month: '2-digit',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                        timeZone: 'Europe/Berlin',
                    }).format(date);
                }
            }
        });
    }
    formatUtcTimes(document);
    document.addEventListener('krt:swapped', function (e) {
        formatUtcTimes(e && e.detail ? e.detail.container : document);
    });

    const switcherForm = document.getElementById('squadron-switcher-form');
    if (switcherForm) {
        const switcherSelect = document.getElementById('squadron-switcher-select');
        if (switcherSelect) {
            switcherSelect.addEventListener('change', function () {
                switcherForm.submit();
            });
        }
    }

    const filterForm = document.getElementById('filter-form');
    if (filterForm) {
        const filterInputs = filterForm.querySelectorAll('input, select');
        filterInputs.forEach((input) => {
            input.addEventListener('change', function () {
                filterForm.submit();
            });
        });

        const submitBtn = filterForm.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.style.display = 'none';
        }
    }
});
