/**
 * Shows the „Tab schließen" button of the info page and closes the tab on click. Browsers refuse
 * `window.close()` for a tab no script opened, so the hint to close it by hand appears when the
 * tab is still open shortly after the click.
 */
(function () {
    const button = document.getElementById("krt-close-tab");
    const hint = document.getElementById("krt-close-tab-hint");
    if (!button || !hint) {
        return;
    }
    button.hidden = false;
    button.addEventListener("click", function () {
        window.close();
        window.setTimeout(function () {
            hint.hidden = false;
        }, 300);
    });
})();
