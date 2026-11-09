# Kestrel Remote

Consent-based Android device status and visible notification manager controlled by a Telegram bot.

## Features
- Pair devices with a short-lived code.
- Show device model, Android version, battery and heartbeat.
- Send visible notifications to paired devices.
- Revoke device access from Telegram.
- Android foreground service starts only after the device owner taps Connect.

The application does not collect SMS, call logs, contacts, files, camera, microphone, or location.

## Structure
- `android/`: Android app
- `server/`: Python backend
- `.github/workflows/android-apk.yml`: APK build workflow

## Setup
Set `BOT_TOKEN` and `ADMIN_ID` in a local `server/.env` file. Run the backend with Python 3.11+ using `pip install -r server/requirements.txt` then `python server/main.py`. Use HTTPS for production.
