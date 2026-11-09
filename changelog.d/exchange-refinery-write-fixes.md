### Fixed
- Exchange refinery orders: a change set naming one material twice in different ways no longer fails with a server error, and a create returns its new goods ids in the order of its goods.
- Exchange refinery orders: several store lines on one good book their sum into the good's yield, and a dry run decides later ops on those yields as the real run does.
- Exchange refinery orders: confirming a held change set re-checks the store capability and decides job orders as the client's own request would, not under the browser session's role or pin.
- Exchange refinery orders: a create needs at least one good and the last good cannot be removed (`GOODS_REQUIRED`); store lines on a completed order are `ORDER_STORED`.
- Exchange refinery orders: concurrent creates under one key no longer duplicate an order, a set never locks another member's order, a resent cancel is `unchanged`, deletes that remove nothing no longer trip the mass-change guard, and fields an op does not use are ignored.
