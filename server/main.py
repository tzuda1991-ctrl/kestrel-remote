# Python 3.11+ | server/main.py | FastAPI + Telegram bot | consent-based device manager
import asyncio
import hashlib
import os
import secrets
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes
import uvicorn

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

BOT_TOKEN = os.environ["BOT_TOKEN"]
ADMIN_ID = int(os.environ["ADMIN_ID"])
DB_PATH = BASE_DIR / "kestrel.sqlite3"

api = FastAPI(title="Kestrel Remote API", version="1.0.0")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def connect_db():
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def initialize_db():
    with connect_db() as conn:
        conn.executescript("""
        CREATE TABLE IF NOT EXISTS pair_codes (
            code TEXT PRIMARY KEY,
            expires_at REAL NOT NULL
        );
        CREATE TABLE IF NOT EXISTS devices (
            device_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            model TEXT,
            android_version TEXT,
            battery INTEGER,
            last_seen TEXT,
            token_hash TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS commands (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
            command TEXT NOT NULL,
            payload TEXT NOT NULL,
            created_at TEXT NOT NULL,
            completed_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_commands_pending
            ON commands(device_id, completed_at, id);
        """)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def require_admin(update: Update) -> bool:
    return bool(
        update.effective_user
        and update.effective_user.id == ADMIN_ID
        and update.effective_chat
        and update.effective_chat.type == "private"
    )


def authenticate_device(device_id: str, authorization: str | None):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Bearer token required")
    supplied_hash = hash_token(authorization[7:])
    with connect_db() as conn:
        row = conn.execute(
            "SELECT token_hash FROM devices WHERE device_id=?",
            (device_id,),
        ).fetchone()
    if not row or not secrets.compare_digest(row["token_hash"], supplied_hash):
        raise HTTPException(status_code=403, detail="Invalid device credentials")


class PairRequest(BaseModel):
    code: str = Field(min_length=8, max_length=8)
    device_id: str = Field(min_length=8, max_length=80)
    name: str = Field(min_length=1, max_length=100)


class HeartbeatRequest(BaseModel):
    model: str = Field(max_length=120)
    android_version: str = Field(max_length=40)
    battery: int = Field(ge=0, le=100)


class ResultRequest(BaseModel):
    command_id: int = Field(gt=0)
    result: str = Field(max_length=300)


@api.get("/health")
def health():
    return {"ok": True, "service": "kestrel-remote"}


@api.post("/api/pair")
def pair_device(request: PairRequest):
    now = time.time()
    with connect_db() as conn:
        code_row = conn.execute(
            "SELECT expires_at FROM pair_codes WHERE code=?",
            (request.code.upper(),),
        ).fetchone()
        if not code_row or code_row["expires_at"] < now:
            raise HTTPException(status_code=400, detail="Pairing code invalid or expired")

        if conn.execute(
            "SELECT 1 FROM devices WHERE device_id=?",
            (request.device_id,),
        ).fetchone():
            raise HTTPException(status_code=409, detail="Device is already paired")

        conn.execute("DELETE FROM pair_codes WHERE code=?", (request.code.upper(),))
        token = secrets.token_urlsafe(32)
        conn.execute(
            """INSERT INTO devices(device_id, name, last_seen, token_hash)
               VALUES (?, ?, ?, ?)""",
            (request.device_id, request.name, utc_now(), hash_token(token)),
        )
    return {"device_id": request.device_id, "token": token}


@api.post("/api/devices/{device_id}/heartbeat")
def heartbeat(
    device_id: str,
    request: HeartbeatRequest,
    authorization: str | None = Header(default=None),
):
    authenticate_device(device_id, authorization)
    with connect_db() as conn:
        conn.execute(
            """UPDATE devices
               SET model=?, android_version=?, battery=?, last_seen=?
               WHERE device_id=?""",
            (request.model, request.android_version, request.battery, utc_now(), device_id),
        )
    return {"ok": True}


@api.get("/api/devices/{device_id}/commands")
def poll_commands(
    device_id: str,
    authorization: str | None = Header(default=None),
):
    authenticate_device(device_id, authorization)
    with connect_db() as conn:
        rows = conn.execute(
            """SELECT id, command, payload FROM commands
               WHERE device_id=? AND completed_at IS NULL
               ORDER BY id LIMIT 10""",
            (device_id,),
        ).fetchall()
    return {"commands": [dict(row) for row in rows]}


@api.post("/api/devices/{device_id}/result")
def command_result(
    device_id: str,
    request: ResultRequest,
    authorization: str | None = Header(default=None),
):
    authenticate_device(device_id, authorization)
    with connect_db() as conn:
        cursor = conn.execute(
            """UPDATE commands SET completed_at=?
               WHERE id=? AND device_id=? AND completed_at IS NULL""",
            (utc_now(), request.command_id, device_id),
        )
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="Pending command not found")
    return {"ok": True}


def enqueue_notification(device_id: str, message: str) -> bool:
    with connect_db() as conn:
        if not conn.execute(
            "SELECT 1 FROM devices WHERE device_id=?", (device_id,)
        ).fetchone():
            return False
        conn.execute(
            """INSERT INTO commands(device_id, command, payload, created_at)
               VALUES (?, 'notify', ?, ?)""",
            (device_id, message, utc_now()),
        )
    return True


async def cmd_newpair(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not require_admin(update):
        return
    code = secrets.token_hex(4).upper()
    with connect_db() as conn:
        conn.execute("DELETE FROM pair_codes WHERE expires_at < ?", (time.time(),))
        conn.execute(
            "INSERT INTO pair_codes(code, expires_at) VALUES (?, ?)",
            (code, time.time() + 300),
        )
    await update.effective_message.reply_text(
        f"Pairing code: {code}\nExpires in 5 minutes. Enter it in the app on the device owner's phone."
    )


async def cmd_devices(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not require_admin(update):
        return
    with connect_db() as conn:
        rows = conn.execute(
            """SELECT device_id, name, model, android_version, battery, last_seen
               FROM devices ORDER BY name"""
        ).fetchall()
    if not rows:
        await update.effective_message.reply_text("No paired devices.")
        return
    parts = []
    for row in rows:
        parts.append(
            f"ID: {row['device_id']}\nName: {row['name']}\n"
            f"Model: {row['model'] or 'Unknown'}\nAndroid: {row['android_version'] or 'Unknown'}\n"
            f"Battery: {row['battery'] if row['battery'] is not None else '?'}%\n"
            f"Last seen: {row['last_seen'] or 'Never'}"
        )
    await update.effective_message.reply_text("\n\n".join(parts))


async def cmd_status(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not require_admin(update):
        return
    if len(context.args) != 1:
        await update.effective_message.reply_text("Usage: /status DEVICE_ID")
        return
    with connect_db() as conn:
        row = conn.execute(
            "SELECT * FROM devices WHERE device_id=?", (context.args[0],)
        ).fetchone()
    if not row:
        await update.effective_message.reply_text("Device not found.")
        return
    await update.effective_message.reply_text(
        f"Name: {row['name']}\nModel: {row['model'] or 'Unknown'}\n"
        f"Android: {row['android_version'] or 'Unknown'}\n"
        f"Battery: {row['battery'] if row['battery'] is not None else '?'}%\n"
        f"Last seen: {row['last_seen'] or 'Never'}"
    )


async def cmd_notify(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not require_admin(update):
        return
    if len(context.args) < 2:
        await update.effective_message.reply_text("Usage: /notify DEVICE_ID message")
        return
    device_id = context.args[0]
    message = " ".join(context.args[1:]).strip()
    if len(message) > 500:
        await update.effective_message.reply_text("Message must be 500 characters or fewer.")
        return
    if not enqueue_notification(device_id, message):
        await update.effective_message.reply_text("Device not found.")
        return
    await update.effective_message.reply_text("Visible notification queued.")


async def cmd_revoke(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not require_admin(update):
        return
    if len(context.args) != 1:
        await update.effective_message.reply_text("Usage: /revoke DEVICE_ID")
        return
    with connect_db() as conn:
        cursor = conn.execute(
            "DELETE FROM devices WHERE device_id=?", (context.args[0],)
        )
    await update.effective_message.reply_text(
        "Device credential revoked." if cursor.rowcount else "Device not found."
    )


async def run():
    initialize_db()
    bot = Application.builder().token(BOT_TOKEN).build()
    bot.add_handler(CommandHandler("newpair", cmd_newpair))
    bot.add_handler(CommandHandler("devices", cmd_devices))
    bot.add_handler(CommandHandler("status", cmd_status))
    bot.add_handler(CommandHandler("notify", cmd_notify))
    bot.add_handler(CommandHandler("revoke", cmd_revoke))

    config = uvicorn.Config(
        api,
        host=os.getenv("API_HOST", "0.0.0.0"),
        port=int(os.getenv("API_PORT", "8000")),
        log_level="info",
    )
    server = uvicorn.Server(config)

    async with bot:
        await bot.start()
        await bot.updater.start_polling()
        try:
            await server.serve()
        finally:
            await bot.updater.stop()
            await bot.stop()


if __name__ == "__main__":
    asyncio.run(run())
