#!/bin/bash
# Habibi Bakery: botni va Mini App'ni bitta buyruq bilan ishga tushirish.
#   ./start.sh        — ishga tushirish
#   Ctrl+C            — botni to'xtatish (tunnel ishlab qoladi, qayta ishga tushirganda manzil o'zgarmaydi)
#   ./stop.sh         — hammasini to'xtatish (bot va tunnel)
set -e
cd "$(dirname "$0")"
mkdir -p logs

echo "🧁 Habibi Bakery ishga tushirilmoqda..."

# 1) Ma'lumotlar bazasi (PostgreSQL)
if ! pg_isready -q; then
  echo "🗄  PostgreSQL ishga tushirilmoqda..."
  brew services start postgresql@18 >/dev/null
  sleep 3
fi
pg_isready -q || { echo "❌ PostgreSQL ishlamayapti. 'brew services start postgresql@18' ni tekshiring."; exit 1; }
echo "✅ Baza ishlayapti"

# 2) Oldingi nusxalarni to'xtatish (aks holda port band va Telegram 409 xatosi)
OLD=$(lsof -ti tcp:8080 || true)
if [ -n "$OLD" ]; then
  echo "♻️  Oldingi bot to'xtatilmoqda..."
  kill $OLD 2>/dev/null || true
  sleep 3
fi
# 3) Tunnel — Mini App uchun https manzil.
#    Tunnel ishlab turgan bo'lsa — o'sha qayta ishlatiladi (manzil o'zgarmaydi, Cloudflare bloklamaydi).
URL=$(grep -E '^WEB_APP_URL=' .env | cut -d= -f2- || true)
# tunnel ishlayaptimi — manzil haqiqatan javob beradimi (Cloudflare tunnelni o'chirib qo'yishi mumkin)
alive() { [ "$(curl -s -o /dev/null -m 10 -w '%{http_code}' "$1/" || true)" != "000" ]; }
if pgrep -f "cloudflared tunnel --no-autoupdate --url http://localhost:8080" >/dev/null && [ -n "$URL" ] \
   && grep -q "$URL" logs/tunnel.log 2>/dev/null && alive "$URL"; then
  echo "♻️  Tunnel ishlab turibdi, manzil o'zgarmaydi"
else
  pkill -f "cloudflared tunnel --no-autoupdate --url http://localhost:8080" 2>/dev/null || true
  echo "🌐 Tunnel ochilmoqda..."
  : > logs/tunnel.log
  # Alohida sessiyada — bot qayta ishga tushganda tunnel o'chib ketmasin
  # (…&) — ota jarayon darhol chiqadi, tunnel tizimga «yetim» bo'lib o'tadi va bot bilan birga o'chmaydi
  ( python3 -c 'import os,sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' \
    cloudflared tunnel --no-autoupdate --url http://localhost:8080 > logs/tunnel.log 2>&1 & )
  URL=""
  for i in $(seq 1 40); do
    URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' logs/tunnel.log | head -1 || true)
    [ -n "$URL" ] && break
    sleep 1
  done
  if [ -z "$URL" ]; then
    echo "❌ Tunnel manzili olinmadi. logs/tunnel.log ni tekshiring."
    pkill -f "cloudflared tunnel --no-autoupdate --url http://localhost:8080" 2>/dev/null || true
    exit 1
  fi
  sed -i '' "s#^WEB_APP_URL=.*#WEB_APP_URL=$URL#" .env
fi
echo "✅ Mini App manzili: $URL"

# 4) Ishlab turganda Mac uxlamasin; chiqishda hammasi to'xtasin
caffeinate -dims -w $$ &
# Tunnel bot bilan birga o'chmaydi — hammasini to'xtatish: ./stop.sh

# 5) Bot
echo "🤖 Bot ishga tushirilmoqda (30 soniyacha)... To'xtatish: Ctrl+C"
echo "   «Telegram bot işga tuşdi» yozuvi chiqqach, Telegram'da /start bosing."
./mvnw -q spring-boot:run 2>&1 | tee logs/app.log | grep --line-buffered -E "işga tuşdi|ERROR|APPLICATION FAILED"
