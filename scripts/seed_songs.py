#!/usr/bin/env python3
"""Siembra canciones de ejemplo en la colección 'songs' de Firestore.

Uso:
  python3 scripts/seed_songs.py /ruta/a/service-account-key.json

Requiere una service account con acceso a Cloud Firestore y el campo
'project_id' en la clave. Reejecutar el script crea documentos duplicados.
"""

import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

DATABASE_SCOPE = "https://www.googleapis.com/auth/datastore"

SONGS = [
    {"youtubeId": "dQw4w9WgXcQ", "title": "Never Gonna Give You Up", "artist": "Rick Astley", "durationSeconds": 213},
    {"youtubeId": "4NRXx6U8ABQ", "title": "Blinding Lights", "artist": "The Weeknd", "durationSeconds": 200},
    {"youtubeId": "JGwWNGJdvx8", "title": "Shape of You", "artist": "Ed Sheeran", "durationSeconds": 233},
    {"youtubeId": "q0hyYWKXF0Q", "title": "Dance Monkey", "artist": "Tones and I", "durationSeconds": 209},
    {"youtubeId": "Zi_XLOBDo_Y", "title": "Billie Jean", "artist": "Michael Jackson", "durationSeconds": 294},
    {"youtubeId": "hTWKbfoikeg", "title": "Smells Like Teen Spirit", "artist": "Nirvana", "durationSeconds": 301},
    {"youtubeId": "9bZkp7q19f0", "title": "Gangnam Style", "artist": "PSY", "durationSeconds": 253},
    {"youtubeId": "fJ9rUzIMcZQ", "title": "Bohemian Rhapsody", "artist": "Queen", "durationSeconds": 354},
]


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def sign_rs256(private_key_pem: str, message: str) -> str:
    fd, path = tempfile.mkstemp(suffix=".pem")
    try:
        with os.fdopen(fd, "w") as f:
            f.write(private_key_pem)
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", path],
            input=message.encode(),
            capture_output=True,
            check=True,
        ).stdout
        return b64url(signature)
    finally:
        os.unlink(path)


def build_jwt(client_email: str, private_key_pem: str, token_uri: str) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": client_email,
        "scope": DATABASE_SCOPE,
        "aud": token_uri,
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":")).encode())
        + "."
        + b64url(json.dumps(claims, separators=(",", ":")).encode())
    )
    return signing_input + "." + sign_rs256(private_key_pem, signing_input)


def get_access_token(client_email: str, private_key_pem: str, token_uri: str) -> str:
    jwt = build_jwt(client_email, private_key_pem, token_uri)
    form = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt}
    ).encode()
    request = urllib.request.Request(token_uri, data=form)
    with urllib.request.urlopen(request) as response:
        payload = json.load(response)
    return payload["access_token"]


def seed_songs(project_id: str, access_token: str) -> None:
    url = (
        f"https://firestore.googleapis.com/v1/projects/{project_id}/"
        f"databases/(default)/documents/songs"
    )
    for song in SONGS:
        body = json.dumps(
            {
                "fields": {
                    "youtubeId": {"stringValue": song["youtubeId"]},
                    "title": {"stringValue": song["title"]},
                    "artist": {"stringValue": song["artist"]},
                    "durationSeconds": {"integerValue": song["durationSeconds"]},
                }
            }
        ).encode()
        request = urllib.request.Request(url, data=body, method="POST")
        request.add_header("Authorization", f"Bearer {access_token}")
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request) as response:
                document = json.load(response)
            print(f"OK {document['name'].split('/documents/')[-1]}")
        except urllib.error.HTTPError as error:
            print(f"ERROR en '{song['title']}': {error.code} {error.read().decode()}")
            raise SystemExit(1)


def main() -> None:
    if len(sys.argv) > 1:
        key_path = sys.argv[1]
    else:
        key_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
    if not key_path:
        print("Indica la ruta a la service account o define GOOGLE_APPLICATION_CREDENTIALS.")
        raise SystemExit(1)
    with open(key_path) as f:
        credentials = json.load(f)
    access_token = get_access_token(
        credentials["client_email"], credentials["private_key"], credentials["token_uri"]
    )
    seed_songs(credentials["project_id"], access_token)


if __name__ == "__main__":
    main()
