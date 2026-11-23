# 🧁 Habibi Bakery — Telegram bot + Mini App

Instagram [@habibi__bakery](https://www.instagram.com/habibi__bakery/) uchun buyurtma tizimi.
Mijoz bot ichidagi Mini App'da menyuni ko'radi, savatga qo'shadi va buyurtma beradi. Admin esa buyurtmalarni botda boshqaradi.

**Texnologiyalar:** Java 21 · Spring Boot 4.1 · PostgreSQL · Flyway · TelegramBots 9 · Mini App (HTML/CSS/JS)

## Imkoniyatlar

**Mijoz (Mini App):**
- Kategoriyali menyu, mahsulot rasmlari, «HIT» belgisi, tugagan mahsulotlar
- O'lcham tanlash (butun / yarim / chorak, katta / kichik), savat
- Yetkazib berish yoki olib ketish, sana va vaqt oralig'i
- To'lov: kartaga o'tkazma (chekni botga yuboradi) yoki naqd
- «Buyurtmalarim»: holatini kuzatish
- Telegram'ning och va qorong'i mavzusiga moslashadi

**Admin (bot):**
- Har bir yangi buyurtma tugmali kartochka bo'lib keladi:
  Qabul → Tayyorlanmoqda → Yo'lda / Tayyor → Topshirildi
- Mijoz yuborgan chek rasmi «To'lov tasdiqlandi / Rad etish» tugmalari bilan keladi
- Har bir holat o'zgarishi haqida mijozga avtomatik xabar boradi
- `/buyurtmalar`, `/statistika`, `/stoplist`, `/mahsulotlar`
- `/narx 12 95000` — narxni o'zgartirish
- Rasm + izoh `/rasm 5` — mahsulotga rasm qo'yish

## Ishga tushirish (lokal)

1. **Bot yarating:** @BotFather → `/newbot` → tokenni oling.
2. **Ma'lumotlar bazasi:**
   ```bash
   createdb habibi_bakery
   ```
3. **Sozlamalar:**
   ```bash
   cp .env.example .env    # keyin .env ni to'ldiring
   ```
4. **Mini App uchun https manzil.** Telegram faqat https qabul qiladi. Lokal sinov uchun tunnel ishlating:
   ```bash
   brew install cloudflared
   cloudflared tunnel --url http://localhost:8080
   ```
   Chiqqan `https://....trycloudflare.com` manzilini `.env` dagi `WEB_APP_URL` ga yozing.
5. **Ishga tushiring:**
   ```bash
   ./mvnw spring-boot:run
   ```
6. **Admin chatni sozlang.** Botga (yoki bot qo'shilgan guruhga) `/admin_id` yozing, chiqqan raqamni `ADMIN_CHAT_ID` ga yozing va ilovani qayta ishga tushiring.

> Faqat dizaynni ko'rish uchun: `.env` da `DEV_MODE=true` qilib, brauzerda http://localhost:8080 ni oching.
> Serverda `DEV_MODE` doim `false` bo'lishi shart.

## Testlar
```bash
./mvnw test
```

## Tuzilma
```
src/main/java/uz/habibi/bakery/
├── bot/       HabibiBot (xabarlar, admin buyruqlari), BotNotifier (bildirishnomalar)
├── web/       REST API, Telegram initData tekshiruvi
├── service/   Buyurtma va menyu mantig'i
├── domain/    JPA entity'lar
└── repo/      Repository'lar
src/main/resources/
├── static/          Mini App (index.html, styles.css, app.js)
└── db/migration/    V1 jadvallar, V2 menyu (oktabr prays-listi)
```

## Keyingi qadamlar
- [ ] Click / Payme integratsiyasi (`PaymentMethod` ga qo'shiladi)
- [ ] Serverga joylash (VPS + domen + webhook)
