### Fixed

- SC Wiki vehicle sync: when several Wiki vehicles share a name and one of them is the base of the others
  (`RSI_Galaxy` and its `_TEMP_` modules), the base links the stored ship type and the variants count as covered.
  The Galaxy gets its Wiki data, and the daily Wiki sweep is no longer blocked.
