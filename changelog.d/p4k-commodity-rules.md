### Changed

- P4K import: commodities now match the SC Wiki by their in-game resource type, the three SLAM packagings count as one
  commodity, and materials without a category take the one their resource type names, so most commodity review cases
  and identity-mismatch report rows disappear.
- Materials the game files do not contain open no category review case, and UEX commodities no game source knows that
  have no price and no use (Boron, Xenon, Coal …) are hidden from the lists and pickers until a source or a price
  brings them back.
- Two material categories of the same name are told apart by their game class name, e.g. "Hergestellte Produkte (Bulk
  Supplies)".
