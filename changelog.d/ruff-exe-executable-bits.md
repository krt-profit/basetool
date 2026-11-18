### Fixed

- Build: Python scripts with a shebang are committed executable and import-only modules carry none, so
  `ruffLint` passes on Linux CI again (REQ-OPS-060).
