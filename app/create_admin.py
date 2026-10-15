"""Interactively create/promote the initial account without exposing credentials."""

from __future__ import annotations

import getpass
import re
import uuid
from contextlib import closing
from datetime import datetime, timezone

from app.main import database, password_hash


def main() -> None:
    email = input("Admin email: ").strip().lower()
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
        raise SystemExit("Invalid email address")
    password = getpass.getpass("Admin password (input hidden): ")
    confirmation = getpass.getpass("Repeat password: ")
    if password != confirmation:
        raise SystemExit("Passwords do not match")
    if len(password) < 8:
        raise SystemExit("Password must contain at least 8 characters")
    now = datetime.now(timezone.utc).isoformat()
    with closing(database()) as connection:
        row = connection.execute(
            "SELECT id FROM accounts WHERE email=? COLLATE NOCASE", (email,),
        ).fetchone()
        if row:
            user_id = row["id"]
            connection.execute(
                "UPDATE accounts SET password_hash=?, username=email, is_admin=1, disabled=0, approved=1 WHERE id=?",
                (password_hash.hash(password), user_id),
            )
        else:
            user_id = uuid.uuid4().hex
            connection.execute(
                """INSERT INTO accounts(id, email, username, password_hash, is_admin, created_at, approved)
                   VALUES (?, ?, ?, ?, 1, ?, 1)""",
                (user_id, email, email, password_hash.hash(password), now),
            )
        # Existing profiles belong to the first admin account. Their profile IDs
        # and profile_state rows stay intact, preserving each profile's data.
        profiles = connection.execute(
            "UPDATE user_profiles SET user_id=? WHERE user_id IS NULL", (user_id,),
        ).rowcount
        # Keep already paired installations working, now scoped to the admin account.
        connection.execute(
            "UPDATE client_devices SET user_id=? WHERE user_id IS NULL", (user_id,),
        )
        connection.commit()
    password = ""
    confirmation = ""
    print(f"Admin account ready for {email}. Existing unassigned profiles linked: {profiles}.")


if __name__ == "__main__":
    main()
