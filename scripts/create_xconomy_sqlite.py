#!/usr/bin/env python3
"""Create a fresh XConomy 2.26.3 SQLite test folder (Python standard library only)."""
import argparse
from decimal import Decimal, InvalidOperation
import hashlib
from pathlib import Path
import re
import sqlite3
import uuid
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="New XConomy directory; must not exist")
    parser.add_argument("--balance", required=True, help="Actual old tax balance in currency units, not cents")
    parser.add_argument("--xconomy-jar", required=True, type=Path, help="XConomy 2.26.3 jar, used for complete default configs")
    parser.add_argument("--name", default="tax", help="Exact old account name, including case")
    parser.add_argument("--uuid", type=uuid.UUID, help="Account UUID resolved by the test server; defaults to offline UUID")
    args = parser.parse_args()
    if args.name.lower() != "tax":
        parser.error("--name must be tax (case variations such as Tax are allowed)")
    try:
        balance = Decimal(args.balance)
        if not balance.is_finite() or balance < 0 or balance != balance.quantize(Decimal("0.01")):
            raise ValueError()
        # Match SQLite's numeric storage, rejecting values that lose cents.
        if Decimal(str(float(balance))) != balance:
            raise ValueError()
    except (InvalidOperation, ValueError, OverflowError):
        parser.error("--balance must be a nonnegative finite amount with at most two decimal places, representable by SQLite")
    account_id = args.uuid or uuid.UUID(
        bytes=hashlib.md5(("OfflinePlayer:" + args.name).encode("utf-8")).digest(), version=3)
    try:
        with zipfile.ZipFile(args.xconomy_jar) as jar:
            configs = {name: jar.read(name).decode("utf-8-sig").replace("\r", "")
                       for name in ("config.yml", "database.yml")}
        for name, key, value in (("database.yml", "storage-type", "SQLite"),
                                 ("database.yml", "usepool", "false"),
                                 ("database.yml", "path", "Default"),
                                 ("config.yml", "UUID-mode", "Default"),
                                 ("config.yml", "Importdata-mode", "false"),
                                 ("config.yml", "initial-bal", "0")):
            configs[name], count = re.subn(r"(?m)^([ \t]*" + re.escape(key) + r":)[^\n]*$",
                                          lambda match: match[1] + " " + value, configs[name])
            if count != 1:
                raise ValueError(f"Unexpected default config: {name}, {key}")
        # Keep the generated fixture isolated from any provider sync channel.
        configs["config.yml"] = re.sub(r"(?m)^([ \t]+enable:)[^\n]*$", r"\1 false", configs["config.yml"])
    except (OSError, zipfile.BadZipFile, KeyError, ValueError) as error:
        parser.error(str(error))
    # Never overwrite a live or previously prepared provider directory.
    try:
        args.output.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        parser.error("output already exists; choose a new test directory")
    data = args.output / "playerdata"
    data.mkdir()
    with sqlite3.connect(data / "data.db") as connection:
        connection.execute("CREATE TABLE xconomy(UID varchar(50) not null, player varchar(50) not null, "
                           "balance double(20,2) not null, hidden int(5) not null, primary key (UID))")
        connection.execute("INSERT INTO xconomy VALUES (?, ?, ?, 0)",
                           (str(account_id), args.name, str(balance)))
        assert connection.execute("PRAGMA integrity_check").fetchall() == [("ok",)]
    for name, content in configs.items():
        (args.output / name).write_text(content, encoding="utf-8")
    print(f"Created {args.output.resolve()}\nAccount: {args.name}\nUUID: {account_id}\nBalance: {balance:.2f}")
    if args.uuid is None:
        print("UUID uses the exact name's offline identity; use --uuid if the test server resolves a different UUID.")


if __name__ == "__main__":
    main()
