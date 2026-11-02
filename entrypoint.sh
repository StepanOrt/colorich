#!/usr/bin/env bash
set -e

echo "=== Starting Colorich Service Entrypoint (Non-Root) ==="
echo "Current user: $(id -un) (UID: $(id -u), GID: $(id -g)), HOME: $HOME"

mkdir -p "$HOME/.gemini" "$HOME/.gemini/antigravity-cli" "$HOME/.config/antigravity"

# 1. If GEMINI_API_KEY is supplied, configure agy for direct API key usage
if [ -n "$GEMINI_API_KEY" ]; then
    echo "Found GEMINI_API_KEY in environment. Configuring agy for direct API key usage..."
    cat <<EOF > "$HOME/.gemini/antigravity-cli/settings.json"
{
  "modelProvider": "gemini",
  "permissions": {
    "autoApprove": true
  },
  "dangerouslySkipPermissions": true
}
EOF
fi

# 2. Assemble Antigravity OAuth credentials if injected as Base64
if [ -n "$AGY_OAUTH_CREDS_BASE64" ]; then
    echo "Found AGY_OAUTH_CREDS_BASE64. Reassembling ~/.gemini/oauth_creds.json..."
    echo "$AGY_OAUTH_CREDS_BASE64" | base64 -d > "$HOME/.gemini/oauth_creds.json"
    chmod 600 "$HOME/.gemini/oauth_creds.json" 2>/dev/null || true
fi

# 3. Ensure permissions and settings are configured for headless execution
if [ ! -f "$HOME/.gemini/antigravity-cli/settings.json" ]; then
    cat <<EOF > "$HOME/.gemini/antigravity-cli/settings.json"
{
  "permissions": {
    "autoApprove": true
  },
  "dangerouslySkipPermissions": true
}
EOF
fi

# 4. Check agy binary
if command -v agy >/dev/null 2>&1; then
    echo "Found Antigravity CLI at $(command -v agy)"
else
    echo "WARNING: 'agy' command not found in PATH."
fi

# 5. Check printer configuration
if [ -S "${CUPS_SERVER:-/run/cups/cups.sock}" ]; then
    echo "Found CUPS UNIX domain socket at ${CUPS_SERVER:-/run/cups/cups.sock}"
elif [ "$DRY_RUN" != "true" ]; then
    echo "Target CUPS server configured at: ${CUPS_SERVER:-/run/cups/cups.sock}"
fi

echo "=== Launching Application ==="
exec java -jar /app/colorich.jar "$@"
