#!/usr/bin/env python3
"""Collect one calendar month of Kartellbank figures from the production ledger.

Runs one read-only psql session on the production host over SSH and prints the
reconciled figures. Pass the host with ``--host`` or set ``BASETOOL_PROD_HOST``.

Usage:
    python .claude/skills/bank-update/scripts/fetch_bank_figures.py
    python .claude/skills/bank-update/scripts/fetch_bank_figures.py --month 2026-09
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from datetime import date, timedelta
from decimal import Decimal

DB_CONTAINER = "db-backend"
DB_PORT = "15432"

SERVICE_USER = "iri"

WINDOWS_SSH = r"C:\Windows\System32\OpenSSH\ssh.exe"


def resolve_ssh() -> str:
    """Return the ssh executable to use, preferring Windows OpenSSH where present."""
    if os.name == "nt" and os.path.exists(WINDOWS_SSH):
        return WINDOWS_SSH
    found = shutil.which("ssh")
    if not found:
        sys.exit("ssh not found on PATH.")
    return found


def previous_month(today: date) -> tuple[int, int]:
    """Return (year, month) of the calendar month before ``today``'s month."""
    return (today.year - 1, 12) if today.month == 1 else (today.year, today.month - 1)


def next_month(year: int, month: int) -> tuple[int, int]:
    """Return (year, month) of the calendar month after the given one."""
    return (year + 1, 1) if month == 12 else (year, month + 1)


def build_sql(year: int, month: int) -> str:
    """Build the labelled read-only figure query for one calendar month.

    Month boundaries are resolved by PostgreSQL in ``Europe/Berlin``.
    """
    ny, nm = next_month(year, month)
    frm = f"{year:04d}-{month:02d}-01 00:00:00"
    to = f"{ny:04d}-{nm:02d}-01 00:00:00"

    return f"""SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY;
WITH b AS (
  SELECT (timestamp '{frm}' AT TIME ZONE 'Europe/Berlin') AS f,
         (timestamp '{to}'  AT TIME ZONE 'Europe/Berlin') AS t
),
krt AS (SELECT id FROM bank_account WHERE type = 'CARTEL')
SELECT 'krt_accounts',  COUNT(*)::text FROM krt
UNION ALL SELECT 'accounts_active', COUNT(*)::text FROM bank_account WHERE status = 'ACTIVE'
UNION ALL SELECT 'accounts_moved', COUNT(DISTINCT p.account_id)::text
  FROM bank_posting p, b WHERE p.created_at >= b.f AND p.created_at < b.t

UNION ALL SELECT 'bank_open',  COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p, b WHERE p.created_at < b.f
UNION ALL SELECT 'bank_close', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p, b WHERE p.created_at < b.t
UNION ALL SELECT 'krt_open',   COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN krt k ON k.id = p.account_id, b WHERE p.created_at < b.f
UNION ALL SELECT 'krt_close',  COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN krt k ON k.id = p.account_id, b WHERE p.created_at < b.t

UNION ALL SELECT 'bank_dep_in', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type = 'DEPOSIT' AND p.created_at >= b.f AND p.created_at < b.t
UNION ALL SELECT 'bank_wdr_out', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type = 'WITHDRAWAL' AND p.created_at >= b.f AND p.created_at < b.t
UNION ALL SELECT 'bank_xfer_net', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type IN ('TRANSFER', 'HOLDER_TRANSFER') AND p.created_at >= b.f AND p.created_at < b.t
UNION ALL SELECT 'bank_rev_net', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type IN ('REVERSAL', 'WIPE_RESET') AND p.created_at >= b.f AND p.created_at < b.t

UNION ALL SELECT 'krt_dep_in', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN krt k ON k.id = p.account_id
       JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type = 'DEPOSIT' AND p.created_at >= b.f AND p.created_at < b.t
UNION ALL SELECT 'krt_wdr_out', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN krt k ON k.id = p.account_id
       JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type = 'WITHDRAWAL' AND p.created_at >= b.f AND p.created_at < b.t
UNION ALL SELECT 'krt_xfer_net', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN krt k ON k.id = p.account_id
       JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type IN ('TRANSFER', 'HOLDER_TRANSFER') AND p.created_at >= b.f AND p.created_at < b.t
UNION ALL SELECT 'krt_rev_net', COALESCE(SUM(p.amount), 0)::text
  FROM bank_posting p JOIN krt k ON k.id = p.account_id
       JOIN bank_transaction t ON t.id = p.transaction_id, b
  WHERE t.type IN ('REVERSAL', 'WIPE_RESET') AND p.created_at >= b.f AND p.created_at < b.t

UNION ALL SELECT 'bank_dep_n', COUNT(*)::text FROM bank_transaction t, b
  WHERE t.type = 'DEPOSIT' AND t.created_at >= b.f AND t.created_at < b.t
UNION ALL SELECT 'bank_wdr_n', COUNT(*)::text FROM bank_transaction t, b
  WHERE t.type = 'WITHDRAWAL' AND t.created_at >= b.f AND t.created_at < b.t
UNION ALL SELECT 'bank_xfer_n', COUNT(*)::text FROM bank_transaction t, b
  WHERE t.type IN ('TRANSFER', 'HOLDER_TRANSFER') AND t.created_at >= b.f AND t.created_at < b.t
UNION ALL SELECT 'bank_rev_n', COUNT(*)::text FROM bank_transaction t, b
  WHERE t.type IN ('REVERSAL', 'WIPE_RESET') AND t.created_at >= b.f AND t.created_at < b.t
UNION ALL SELECT 'krt_dep_n', COUNT(DISTINCT t.id)::text
  FROM bank_transaction t JOIN bank_posting p ON p.transaction_id = t.id
       JOIN krt k ON k.id = p.account_id, b
  WHERE t.type = 'DEPOSIT' AND t.created_at >= b.f AND t.created_at < b.t
UNION ALL SELECT 'krt_wdr_n', COUNT(DISTINCT t.id)::text
  FROM bank_transaction t JOIN bank_posting p ON p.transaction_id = t.id
       JOIN krt k ON k.id = p.account_id, b
  WHERE t.type = 'WITHDRAWAL' AND t.created_at >= b.f AND t.created_at < b.t

UNION ALL SELECT 'bank_fees', COALESCE(SUM(t.transfer_fee), 0)::text
  FROM bank_transaction t, b WHERE t.created_at >= b.f AND t.created_at < b.t
UNION ALL SELECT 'krt_fees', COALESCE(SUM(t.transfer_fee), 0)::text
  FROM bank_transaction t, b
  WHERE t.created_at >= b.f AND t.created_at < b.t
    AND EXISTS (SELECT 1 FROM bank_posting p JOIN krt k ON k.id = p.account_id
                WHERE p.transaction_id = t.id)

UNION ALL SELECT 'req_dep_ok_n', COUNT(*)::text FROM bank_booking_request r, b
  WHERE r.type = 'DEPOSIT' AND r.status = 'CONFIRMED'
    AND r.created_at >= b.f AND r.created_at < b.t
UNION ALL SELECT 'req_wdr_ok_n', COUNT(*)::text FROM bank_booking_request r, b
  WHERE r.type = 'WITHDRAWAL' AND r.status = 'CONFIRMED'
    AND r.created_at >= b.f AND r.created_at < b.t
UNION ALL SELECT 'req_wdr_ok_sum', COALESCE(SUM(r.amount), 0)::text FROM bank_booking_request r, b
  WHERE r.type = 'WITHDRAWAL' AND r.status = 'CONFIRMED'
    AND r.created_at >= b.f AND r.created_at < b.t
UNION ALL SELECT 'req_rejected_n', COUNT(*)::text FROM bank_booking_request r, b
  WHERE r.status = 'REJECTED' AND r.created_at >= b.f AND r.created_at < b.t
UNION ALL SELECT 'req_krt_wdr_ok_n', COUNT(*)::text
  FROM bank_booking_request r JOIN krt k ON k.id = r.account_id, b
  WHERE r.type = 'WITHDRAWAL' AND r.status = 'CONFIRMED'
    AND r.created_at >= b.f AND r.created_at < b.t
UNION ALL SELECT 'req_krt_wdr_ok_sum', COALESCE(SUM(r.amount), 0)::text
  FROM bank_booking_request r JOIN krt k ON k.id = r.account_id, b
  WHERE r.type = 'WITHDRAWAL' AND r.status = 'CONFIRMED'
    AND r.created_at >= b.f AND r.created_at < b.t
UNION ALL SELECT 'req_krt_rej_n', COUNT(*)::text
  FROM bank_booking_request r JOIN krt k ON k.id = r.account_id, b
  WHERE r.status = 'REJECTED' AND r.created_at >= b.f AND r.created_at < b.t
UNION ALL SELECT 'req_krt_rej_sum', COALESCE(SUM(r.amount), 0)::text
  FROM bank_booking_request r JOIN krt k ON k.id = r.account_id, b
  WHERE r.status = 'REJECTED' AND r.created_at >= b.f AND r.created_at < b.t
;
"""


def run_query(host: str, sql: str) -> dict[str, Decimal]:
    """Execute the query on the production host and return the labelled figures."""
    remote = (
        f'cd / && sudo -n -u {SERVICE_USER} podman exec -i {DB_CONTAINER} sh -c '
        f'"psql -qAt -U \\$POSTGRES_USER -d \\$POSTGRES_DB -p {DB_PORT} -f -"'
    )
    proc = subprocess.run(
        [resolve_ssh(), "-o", "BatchMode=yes", host, remote],
        input=sql,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        sys.exit(f"psql failed (exit {proc.returncode}).")

    figures: dict[str, Decimal] = {}
    for line in proc.stdout.splitlines():
        if "|" not in line:
            continue
        label, _, value = line.partition("|")
        try:
            figures[label.strip()] = Decimal(value.strip())
        except Exception:  # noqa: BLE001
            sys.exit(f"Unparsable row from psql: {line!r}")
    if not figures:
        sys.exit("psql returned no rows — is the query or the container name right?")
    return figures


def de(value: Decimal, sign: bool = False) -> str:
    """Format an aUEC amount German-style: dots as thousands separators."""
    quantised = value.quantize(Decimal(1)) if value == value.to_integral_value() else value
    text = f"{abs(quantised):,.0f}".replace(",", ".")
    if value < 0:
        return "\u2212" + text
    return ("+" + text) if sign and value > 0 else text


MONTHS = (
    "Januar", "Februar", "M\u00e4rz", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)


def main() -> None:
    """Parse arguments, run the read-only query and print the reconciled figures."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--month", help="calendar month as YYYY-MM (default: last complete month)")
    parser.add_argument("--host", default=os.environ.get("BASETOOL_PROD_HOST"),
                        help="production host, e.g. user@address (or set BASETOOL_PROD_HOST)")
    parser.add_argument("--allow-partial", action="store_true",
                        help="permit a month that has not ended yet")
    parser.add_argument("--print-sql", action="store_true", help="print the SQL and exit")
    args = parser.parse_args()

    today = date.today()
    if args.month:
        try:
            year, month = (int(part) for part in args.month.split("-", 1))
            date(year, month, 1)
        except Exception:  # noqa: BLE001
            sys.exit(f"--month must be YYYY-MM, got {args.month!r}")
    else:
        year, month = previous_month(today)

    ny, nm = next_month(year, month)
    if date(ny, nm, 1) > today and not args.allow_partial:
        sys.exit(
            f"{MONTHS[month - 1]} {year} is not over yet — the report covers whole "
            f"calendar months. Wait until {ny:04d}-{nm:02d}-01, or pass --allow-partial."
        )

    sql = build_sql(year, month)
    if args.print_sql:
        print(sql)
        return
    if not args.host:
        sys.exit(
            "No production host. Pass --host or set BASETOOL_PROD_HOST. The address is "
            "in the knowledge base, 60 Runbooks/Production Access.md — it is deliberately "
            "not stored in this public repository."
        )

    f = run_query(args.host, sql)

    if f["krt_accounts"] != 1:
        sys.exit(f"Expected exactly one CARTEL account, found {f['krt_accounts']}.")

    bank_net = f["bank_close"] - f["bank_open"]
    krt_net = f["krt_close"] - f["krt_open"]
    bank_parts = f["bank_dep_in"] + f["bank_wdr_out"] + f["bank_xfer_net"] + f["bank_rev_net"]
    krt_parts = f["krt_dep_in"] + f["krt_wdr_out"] + f["krt_xfer_net"] + f["krt_rev_net"]
    share = (f["krt_close"] / f["bank_close"] * 100) if f["bank_close"] else Decimal(0)

    stand = f"01.{nm:02d}.{ny}"
    last_day = (date(ny, nm, 1) - timedelta(days=1)).day
    print(f"MONAT            {MONTHS[month - 1]} {year}")
    print(f"STAND            {stand}")
    print(f"TITEL            Bank Update {MONTHS[month - 1]} {year}")
    print(f"ZEITRAUM         01.{month:02d}.{year} bis {last_day:02d}.{month:02d}.{year}")
    print()
    print("GESAMTE KARTELLBANK")
    print(f"  Kontostand 01.{month:02d}.{year}   {de(f['bank_open']):>18}")
    print(f"  Einzahlungen            {de(f['bank_dep_in'], sign=True):>18}   ({f['bank_dep_n']:.0f} Buchungen)")
    print(f"  Auszahlungen            {de(f['bank_wdr_out'], sign=True):>18}   ({f['bank_wdr_n']:.0f} Buchungen)")
    print(f"  Umbuchungen (netto)     {de(f['bank_xfer_net'], sign=True):>18}   ({f['bank_xfer_n']:.0f} Buchungen)")
    print(f"  Stornierungen (netto)   {de(f['bank_rev_net'], sign=True):>18}   ({f['bank_rev_n']:.0f} Buchungen)")
    print(f"  Saldo                   {de(bank_net, sign=True):>18}")
    print(f"  Kontostand {stand}   {de(f['bank_close']):>18}")
    print(f"  Transfergebuehren       {de(f['bank_fees']):>18}")
    print()
    print("KRT-KONTO")
    print(f"  Kontostand 01.{month:02d}.{year}   {de(f['krt_open']):>18}")
    print(f"  Einzahlungen            {de(f['krt_dep_in'], sign=True):>18}   ({f['krt_dep_n']:.0f} Buchungen)")
    print(f"  Auszahlungen            {de(f['krt_wdr_out'], sign=True):>18}   ({f['krt_wdr_n']:.0f} Buchungen)")
    print(f"  Umbuchungen (netto)     {de(f['krt_xfer_net'], sign=True):>18}")
    print(f"  Stornierungen (netto)   {de(f['krt_rev_net'], sign=True):>18}")
    print(f"  Saldo                   {de(krt_net, sign=True):>18}")
    print(f"  Kontostand {stand}   {de(f['krt_close']):>18}")
    print(f"  Transfergebuehren       {de(f['krt_fees']):>18}")
    print(f"  Anteil am Bankvermoegen {share:>17.1f} %")
    print()
    print("ABRUFE (Buchungsantraege)")
    print(f"  Einzahlungsantraege bestaetigt   {f['req_dep_ok_n']:.0f}")
    print(f"  Auszahlungsantraege bestaetigt   {f['req_wdr_ok_n']:.0f}  ueber {de(f['req_wdr_ok_sum'])} aUEC")
    print(f"  davon KRT-Konto                  {f['req_krt_wdr_ok_n']:.0f}  ueber {de(f['req_krt_wdr_ok_sum'])} aUEC")
    print(f"  abgelehnt gesamt                 {f['req_rejected_n']:.0f}")
    print(f"  davon KRT-Konto                  {f['req_krt_rej_n']:.0f}  ueber {de(f['req_krt_rej_sum'])} aUEC")
    print()
    print("KONTEXT")
    print(f"  Konten mit Bewegung     {f['accounts_moved']:.0f}")
    print(f"  Aktive Konten (heute)   {f['accounts_active']:.0f}")
    print()
    print("GEGENPROBE")
    ok = True
    for name, net, parts in (("Bank", bank_net, bank_parts), ("KRT", krt_net, krt_parts)):
        mark = "OK  " if net == parts else "FEHLER"
        ok = ok and net == parts
        print(f"  {mark} {name}: Anfang + Bewegungen = Ende  ({de(parts)} vs. {de(net)})")
    if not ok:
        sys.exit("Die Gegenprobe geht nicht auf — nichts veroeffentlichen, erst klaeren.")


if __name__ == "__main__":
    main()
