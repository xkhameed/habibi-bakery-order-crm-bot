#!/bin/bash
# Habibi Bakery: bot va tunnelni to'xtatish.
cd "$(dirname "$0")"
OLD=$(lsof -ti tcp:8080 || true)
[ -n "$OLD" ] && kill $OLD 2>/dev/null && echo "🛑 Bot to'xtatildi"
pkill -f "cloudflared tunnel --no-autoupdate --url http://localhost:8080" 2>/dev/null && echo "🛑 Tunnel to'xtatildi"
pkill -f "caffeinate -dims" 2>/dev/null || true
echo "✅ Hammasi to'xtatildi"
