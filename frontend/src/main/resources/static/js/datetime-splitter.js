/**
 * Splits each `.datetime-split-group` into local date and time inputs backed by a hidden UTC value;
 * the one place forms convert between UTC and browser-local time.
 *
 * The hidden value is read as an ISO instant (with `Z` or an offset), an ISO local date-time or a
 * bare date, anything else as local time; it is always written as a UTC instant with seconds and
 * `Z`. `window.krtSyncDatetimeSplitGroup(group)` re-fills the visible parts after the hidden value
 * was set programmatically.
 */
(function () {
    const pad = (n) => String(n).padStart(2, '0');
    const hasZoneInfo = (val) => /Z$|[+-]\d{2}:?\d{2}$/.test(val);
    const isoDateRegex = /^\d{4}-\d{2}-\d{2}$/;
    const isoLocalRegex = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;

    function applyHiddenToParts(hidden, datePart, timePart) {
        if (!hidden || !datePart || !timePart) return;
        if (!hidden.value) {
            datePart.value = '';
            timePart.value = '';
            return;
        }
        const v = hidden.value.trim();
        if (isoDateRegex.test(v)) {
            datePart.value = v;
            timePart.value = '';
        } else if (hasZoneInfo(v)) {
            const d = new Date(v);
            if (!isNaN(d)) {
                datePart.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
                timePart.value = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
            }
        } else if (isoLocalRegex.test(v)) {
            const parts = v.split('T');
            datePart.value = parts[0];
            timePart.value = parts[1].substring(0, 5);
        } else {
            const d = new Date(v);
            if (!isNaN(d)) {
                datePart.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
                timePart.value = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
            }
        }
    }

    function syncGroup(group) {
        if (!group) return;
        const hidden = group.querySelector('input[type="hidden"]');
        const datePart = group.querySelector('.date-part');
        const timePart = group.querySelector('.time-part');
        applyHiddenToParts(hidden, datePart, timePart);
    }

    window.krtSyncDatetimeSplitGroup = syncGroup;

    /**
     * Initializes one `.datetime-split-group`: adds its error element, fills the visible parts from
     * the hidden value and binds the listeners that keep the hidden UTC value current. Idempotent
     * through the `data-krt-dt-initialized` marker.
     */
    function initGroup(group) {
        const hidden = group.querySelector('input[type="hidden"]');
        const datePart = group.querySelector('.date-part');
        const timePart = group.querySelector('.time-part');

        if (!hidden || !datePart || !timePart) return;
        if (group.dataset.krtDtInitialized === '1') return;
        group.dataset.krtDtInitialized = '1';

        const errorDiv = document.createElement('div');
        errorDiv.style.color = 'var(--color-danger-text)';
        errorDiv.style.fontSize = '0.8rem';
        errorDiv.style.marginTop = '0.2rem';
        errorDiv.style.display = 'none';
        group.appendChild(errorDiv);

        applyHiddenToParts(hidden, datePart, timePart);

        const filterRole = group.getAttribute('data-datetime-filter-role');

        const updateHidden = () => {
            errorDiv.textContent = '';
            errorDiv.style.display = 'none';
            const dVal = datePart.value;
            const tVal = timePart.value;

            if (dVal && tVal) {
                const [y, m, d] = dVal.split('-').map(Number);
                const [hh, mm] = tVal.split(':').map(Number);
                const local = new Date(y, m - 1, d, hh, mm, 0, 0);
                hidden.value = isNaN(local) ? '' : local.toISOString();
            } else if (filterRole && (dVal || tVal)) {
                let y, m, d;
                if (dVal) {
                    [y, m, d] = dVal.split('-').map(Number);
                } else {
                    const now = new Date();
                    y = now.getFullYear();
                    m = now.getMonth() + 1;
                    d = now.getDate();
                }
                let hh, mm;
                if (tVal) {
                    [hh, mm] = tVal.split(':').map(Number);
                } else if (filterRole === 'end') {
                    hh = 23;
                    mm = 59;
                } else {
                    hh = 0;
                    mm = 0;
                }
                const local = new Date(y, m - 1, d, hh, mm, 0, 0);
                hidden.value = isNaN(local) ? '' : local.toISOString();
            } else if (dVal) {
                hidden.value = dVal;
            } else {
                hidden.value = '';
            }

            if (hidden.value) {
                const currentDate = new Date();
                const selectedDate = new Date(hidden.value);

                if (group.getAttribute('data-validate-not-past') === 'true') {
                    if (selectedDate < currentDate) {
                        errorDiv.textContent = window.krtI18nText(
                            group.getAttribute('data-error-past'),
                            'data-error-past',
                        );
                        errorDiv.style.display = '';
                    }
                }

                if (group.hasAttribute('data-validate-after')) {
                    const targetId = group.getAttribute('data-validate-after');
                    const targetHidden = document.getElementById(targetId);
                    if (targetHidden && targetHidden.value) {
                        const targetDate = new Date(targetHidden.value);
                        if (selectedDate <= targetDate) {
                            errorDiv.textContent = window.krtI18nText(
                                group.getAttribute('data-error-after'),
                                'data-error-after',
                            );
                            errorDiv.style.display = '';
                        }
                    }
                }
            }

            hidden.dispatchEvent(new Event('change'));
        };

        datePart.addEventListener('change', updateHidden);
        timePart.addEventListener('change', updateHidden);
        datePart.addEventListener('input', updateHidden);
        timePart.addEventListener('input', updateHidden);
    }

    window.krtInitDatetimeSplitGroup = initGroup;

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.datetime-split-group').forEach(initGroup);
    });

    document.addEventListener('krt:swapped', (e) => {
        const root = (e.detail && e.detail.container) || document;
        root.querySelectorAll('.datetime-split-group').forEach(initGroup);
    });
})();
