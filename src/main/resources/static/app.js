/* Habibi Bakery — Telegram Mini App (uz / cyr / ru) */
(() => {
  'use strict';

  const tg = window.Telegram?.WebApp;
  const inTelegram = !!(tg && tg.initData);
  const $ = (s) => document.querySelector(s);
  const $$ = (s) => [...document.querySelectorAll(s)];

  const TASHKENT = { lat: 41.3111, lng: 69.2797 };
  const GROUPED_VIEWS = new Set([]);
  const TABS = ['menu', 'cart', 'profile'];          // pastki tablar (asosiy sahifalar)
  const TABBAR_VIEWS = new Set(['menu', 'catalog', 'category', 'cart', 'profile']); // tablar ko'rinadigan sahifalar
  const TAB_OF = { catalog: 'menu', category: 'menu' };   // ichki sahifa qaysi tabga tegishli
  const CREAM_VIEWS = new Set(['menu', 'catalog', 'category', 'cart', 'checkout', 'success', 'orders', 'profile']);
  const LANGS = ['uz', 'cyr', 'ru'];

  const state = {
    lang: 'uz',
    dict: {},             // /api/i18n tarjimalari
    config: null,
    menu: [],             // kategoriyalar
    variants: new Map(),  // variantId -> {variant, product}
    cart: loadCart(),     // [{variantId, qty}]
    view: 'menu',
    home: 'menu',         // «Asosiy» tab: kirish sahifasi faqat birinchi marta, keyin — mahsulotlar
    history: [],
    scroll: {},           // har bir sahifaning scroll joyi (orqaga qaytganda tiklanadi)
    checkout: { deliveryType: 'DELIVERY', paymentMethod: 'CARD_TRANSFER', date: null, slot: null, location: null },
    submitting: false,
    lastOrder: null,
    activeCat: null,      // Home: tanlangan kategoriya
    liked: new Set(),     // mijoz like bosgan mahsulotlar
    myRatings: {},        // productId → mening bahoyim (1–5)
    purchased: new Set(), // sotib olgan mahsulotlarim — faqat ularga baho bera olaman
  };

  // ------------------------------------------------------------ yordamchilar
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const num = (n) => Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  const money = (n) => num(n) + ' ' + t('som');
  const haptic = (type = 'light') => { try { tg?.HapticFeedback?.impactOccurred(type); } catch (_) { /* yo'q */ } };
  const notify = (type) => { try { tg?.HapticFeedback?.notificationOccurred(type); } catch (_) { /* yo'q */ } };

  /** Tarjima: t('app.added', 'Dubai torti') -> «Dubai torti» savatga qo'shildi */
  function t(key, ...args) {
    let s = state.dict[key] ?? key;
    args.forEach((a, i) => { s = s.split(`{${i}}`).join(a); });
    return s;
  }
  const months = () => (state.dict.months || '').split(',');
  const weekdays = () => (state.dict.weekdays || '').split(',');
  const fmtDate = (d) => t('date.format', d.getDate(), months()[d.getMonth()] || '');

  function storage(key, value) {
    try {
      if (value === undefined) return JSON.parse(localStorage.getItem(key));
      localStorage.setItem(key, JSON.stringify(value));
    } catch (_) { return null; }
    return null;
  }
  // ---- Kirish sahifasi faqat bir marta: shu qurilmada (localStorage) va Telegram bulutida (CloudStorage)
  const WELCOME_KEY = 'hb_welcome_seen';
  function markWelcomeSeen() {
    storage(WELCOME_KEY, true);
    try { if (tg?.isVersionAtLeast?.('6.9')) tg.CloudStorage.setItem(WELCOME_KEY, '1'); } catch (_) { /* eski versiya */ }
  }
  function checkWelcomeSeen(cb) {
    if (storage(WELCOME_KEY)) return cb(true);
    try {
      if (tg?.isVersionAtLeast?.('6.9')) { tg.CloudStorage.getItem(WELCOME_KEY, (err, v) => cb(!err && v === '1')); return; }
    } catch (_) { /* eski versiya */ }
    cb(false);
  }
  function skipWelcome() {
    state.home = 'catalog';
    if (state.view === 'menu') { state.history = []; go('catalog', { push: false }); }
  }

  function loadCart() { const c = storage('hb_cart'); return Array.isArray(c) ? c : []; }
  function saveCart() { storage('hb_cart', state.cart); }

  let toastTimer;
  function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.hidden = false;
    el.style.animation = 'none';
    void el.offsetWidth;
    el.style.animation = '';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 2600);
  }

  async function api(path, options = {}) {
    const res = await fetch(path, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-Init-Data': tg?.initData || '',
        'X-Lang': state.lang,
        ...(options.headers || {}),
      },
    });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.error || t('app.err.generic'));
    return body;
  }

  // Telegram sarlavha rangini sahifa foniga moslash (dizayn doim och rangda)
  function paintChrome() {
    const cream = CREAM_VIEWS.has(state.view);
    const color = cream ? '#F6F3EF' : GROUPED_VIEWS.has(state.view) ? '#F6F3EF' : '#FFFFFF';
    document.body.classList.toggle('grouped-bg', GROUPED_VIEWS.has(state.view));
    document.body.classList.toggle('cream-bg', cream);
    document.body.classList.toggle('has-tabs', TABBAR_VIEWS.has(state.view));
    try { tg?.setHeaderColor(color); tg?.setBackgroundColor(color); tg?.setBottomBarColor?.('#FFFFFF'); } catch (_) { /* eski versiya */ }
  }

  // ------------------------------------------------------------ til
  /** URL (?lang=) → oldin tanlangan → Telegram tili → o'zbekcha */
  function initialLang() {
    const fromUrl = new URLSearchParams(location.search).get('lang');
    if (LANGS.includes(fromUrl)) return { lang: fromUrl, sure: true };
    const saved = storage('hb_lang');
    if (LANGS.includes(saved)) return { lang: saved, sure: true };
    const tgLang = tg?.initDataUnsafe?.user?.language_code || navigator.language || '';
    return { lang: tgLang.toLowerCase().startsWith('ru') ? 'ru' : 'uz', sure: false };
  }

  /** HTML'dagi data-i18n matnlarini to'ldirish. */
  function applyStatic() {
    document.documentElement.lang = state.lang === 'ru' ? 'ru' : 'uz';
    // Tarjima topilmasa (masalan, bot hali eski versiyada) — kalit nomi emas, HTML'dagi tayyor matn qoladi
    $$('[data-i18n]').forEach((el) => {
      const key = el.dataset.i18n;
      if (key in state.dict) el.textContent = t(key);
    });
    $$('[data-i18n-ph]').forEach((el) => {
      if (el.dataset.i18nPh in state.dict) el.placeholder = t(el.dataset.i18nPh);
    });
    $('#lang-current').textContent = { uz: "Özbekça", cyr: 'Ўзбекча', ru: 'Русский' }[state.lang];
    renderHello();
    renderProfile();
    $$('#lang-options .row').forEach((r) => r.classList.toggle('on', r.dataset.lang === state.lang));
  }

  async function loadContent() {
    const q = `?lang=${state.lang}`;
    const [dict, config, menu] = await Promise.all([api('/api/i18n' + q), api('/api/config' + q), api('/api/menu' + q)]);
    state.dict = dict;
    state.config = config;
    state.menu = menu.categories;
    state.trending = menu.trending || [];   // [{id, count}] — shu oy sotilgan soni
    state.variants = new Map();
    state.menu.forEach((c) => c.products.forEach((p) => p.variants.forEach((v) => state.variants.set(v.id, { variant: v, product: p }))));
  }

  async function setLang(lang) {
    if (!LANGS.includes(lang)) return;
    closeLangSheet();
    if (lang === state.lang) return;
    state.lang = lang;
    storage('hb_lang', lang);
    haptic();
    // Bot xabarlari ham shu tilda kelishi uchun serverga saqlaymiz
    if (inTelegram) api('/api/me/language', { method: 'POST', body: JSON.stringify({ language: lang }) }).catch(() => {});
    try {
      await loadContent();
    } catch (e) {
      toast(e.message);
      return;
    }
    applyStatic();
    renderMenu();
    renderBottom();
    if (state.view === 'cart') renderCart();
    if (state.view === 'checkout') renderCheckout();
    if (state.view === 'orders') renderOrders();
    if (state.view === 'success' && state.lastOrder) renderSuccess(state.lastOrder);
    if (!$('#contact-sheet').hidden) renderContact();
  }

  function openLangSheet() {
    applyStatic();
    $('#lang-sheet').hidden = false;
    $('#lang-backdrop').hidden = false;
    tg?.BackButton.show();
    haptic();
  }
  function closeLangSheet() {
    $('#lang-sheet').hidden = true;
    $('#lang-backdrop').hidden = true;
    syncBackButton();
  }

  // ------------------------------------------------------------ brend nomi
  /**
   * Matnlardagi «Habibi Bakery» — to'qroq rangda va qalinroq (gap ichida ajralib tursin).
   * Sarlavhalar (o'zi brend nomi bo'lgan joylar) va kiritish maydonlari tegilmaydi.
   */
  const BRAND_RE = /Habibi Bakery|Хабиби Бейкери|Ҳабиби Бейкери/;
  function highlightBrand(root = document.body) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode(n) {
        if (!BRAND_RE.test(n.nodeValue)) return NodeFilter.FILTER_REJECT;
        const el = n.parentElement;
        if (!el || el.closest('.brand-strong, .w-brand, .lang-title, script, style, textarea, input, title, button.chip')) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach((n) => {
      const parts = n.nodeValue.split(new RegExp(`(${BRAND_RE.source})`));
      const frag = document.createDocumentFragment();
      parts.forEach((part) => {
        if (BRAND_RE.test(part)) {
          const b = document.createElement('b');
          b.className = 'brand-strong';
          b.textContent = part;
          frag.appendChild(b);
        } else if (part) {
          frag.appendChild(document.createTextNode(part));
        }
      });
      n.parentNode.replaceChild(frag, n);
    });
  }
  // sahifa qayta chizilganda ham (yangi matnlar paydo bo'lsa) — bir kadrda bir marta
  let brandQueued = false;
  new MutationObserver(() => {
    if (brandQueued) return;
    brandQueued = true;
    requestAnimationFrame(() => { brandQueued = false; highlightBrand(); });
  }).observe(document.body, { childList: true, subtree: true, characterData: true });

  // ------------------------------------------------------------ navigatsiya
  function go(view, { push = true } = {}) {
    if (state.view === view) return;
    if (state.view === 'menu' && view !== 'menu') state.home = 'catalog';   // kirishdan chiqildi — endi «Asosiy» = mahsulotlar
    state.scroll[state.view] = window.scrollY;
    if (push) state.history.push(state.view);
    state.view = view;
    $$('.view').forEach((v) => v.classList.toggle('active', v.id === 'view-' + view));
    // Yangi sahifa tepadan, orqaga qaytilganda esa avvalgi joydan ochiladi
    window.scrollTo(0, push ? 0 : (state.scroll[view] || 0));
    if (view === 'cart') renderCart();
    if (view === 'checkout') renderCheckout();
    if (view === 'orders') renderOrders();
    if (view === 'map') openMap();
    if (view === 'addresses') renderAddresses();
    if (view === 'address-edit') renderAddressEdit();
    if (view === 'profile') { renderProfile(); loadProfileStats(); }
    if (view === 'menu') fitTaglines();
    const tabOf = view === 'category' ? (state.categoryTab || 'menu') : (TAB_OF[view] || view);
    $$('.tab').forEach((b) => b.classList.toggle('on', b.dataset.tab === tabOf));
    const catBack = $('#view-catalog .sub-back');
    if (catBack) catBack.style.display = state.home === 'catalog' ? 'none' : '';
    paintChrome();
    renderBottom();
    syncBackButton();
  }

  /** Pastki tab bosildi: tarix tozalanadi (tablar orasida «Orqaga» yo'q). */
  function switchTab(view) {
    if (view === 'menu') view = state.home;
    if (TAB_OF[state.view] === view) { state.history = []; go(view, { push: false }); return; }
    if (state.view === view) {
      if (view === state.home) window.scrollTo({ top: 0, behavior: 'smooth' });
      return;
    }
    haptic();
    state.history = [];
    go(view, { push: false });
    window.scrollTo(0, 0);
  }

  /** Telegram «Orqaga» tugmasi: oyna ochiq bo'lsa yoki ichki sahifada — ko'rinadi, tablarda — yo'q. */
  function syncBackButton() {
    if (!tg) return;
    const sheetOpen = !$('#sheet').hidden || !$('#lang-sheet').hidden || !$('#contact-sheet').hidden || !$('#about-sheet').hidden || !$('#terms-sheet').hidden;
    (sheetOpen || !(TABS.includes(state.view) || state.view === state.home || state.view === 'success')) ? tg.BackButton.show() : tg.BackButton.hide();
  }

  function back() {
    if (!$('#lang-sheet').hidden) return closeLangSheet();
    if (!$('#contact-sheet').hidden) return closeContact();
    if (!$('#about-sheet').hidden) return closeAbout();
    if (!$('#terms-sheet').hidden) return closeTerms();
    if (!$('#sheet').hidden) return closeSheet();
    go(state.history.pop() || state.home, { push: false });
  }

  // ------------------------------------------------------------ savat
  const cartQty = (variantId) => state.cart.find((i) => i.variantId === variantId)?.qty || 0;
  const productQty = (product) => product.variants.reduce((s, v) => s + cartQty(v.id), 0);
  const cartCount = () => state.cart.reduce((s, i) => s + i.qty, 0);
  const cartTotal = () => state.cart.reduce((s, i) => s + (state.variants.get(i.variantId)?.variant.price || 0) * i.qty, 0);
  /** Chegirma hisobiga tejaladigan summa (eski narx − yangi narx). */
  const cartSavings = () => state.cart.reduce((s, i) => {
    const v = state.variants.get(i.variantId)?.variant;
    return s + (v && v.oldPrice ? (v.oldPrice - v.price) * i.qty : 0);
  }, 0);

  function setQty(variantId, qty) {
    const item = state.cart.find((i) => i.variantId === variantId);
    if (qty <= 0) state.cart = state.cart.filter((i) => i.variantId !== variantId);
    else if (item) item.qty = Math.min(qty, 50);
    else state.cart.push({ variantId, qty: Math.min(qty, 50) });
    saveCart();
    renderBottom();
  }

  /** Menyudan o'chirilgan yoki tugagan mahsulotlarni savatdan olib tashlaydi. */
  function cleanCart() {
    const before = state.cart.length;
    state.cart = state.cart.filter((i) => state.variants.get(i.variantId)?.product.available);
    if (state.cart.length !== before) { saveCart(); toast(t('app.cart_cleaned')); }
  }

  // ------------------------------------------------------------ menyu
  function productImage(p, cls = 'p-img') {
    // Rasm yuklanmasa, buzilgan belgi o'rniga emoji ko'rinadi
    // Emoji ishlatilmaydi: rasm bo'lmasa (yoki yuklanmasa) yumshoq rangli bo'sh joy ko'rinadi
    return `<div class="${cls} ph-img">${p.photoUrl ? `<img src="${esc(p.photoUrl)}" alt="" loading="lazy" onerror="this.remove()">` : ''}</div>`;
  }

  /** Aksiyada: eski narx ustidan chizilgan, yangi narx to'qroq rangda (hamma joyda bir xil). */
  /** Variantlar o'lchammi (Butun/Yarim, Katta/Kichik) yoki ta'mmi (Malina, Olcha…). */
  const SIZE_WORDS = /^(butun|yarim|çorak|chorak|katta|kiçik|kichik|örtaça|o'rtacha|o‘rtacha|целый|половина|четверть|большой|маленький|средний|бутун|ярим|чорак|катта|кичик|ўртача)/i;
  const isSizeList = (p) => p.variants.every((v) => !v.name || SIZE_WORDS.test(v.name.trim()));

  function priceLabel(p) {
    // kartochkada eng qimmat variant narxi ko'rinadi
    const top = p.variants.reduce((a, b) => (b.price > a.price ? b : a));
    const cur = money(top.price) + (p.perPiece ? ` <small class="per-unit">/ ${t('app.per_piece')}</small>` : '');
    return top.oldPrice ? `<s>${num(top.oldPrice)}</s><span class="p-new">${cur}</span>` : cur;
  }

  // ---- Home: «Salom,» va botdagi ism (tanlangan tilda)
  function displayName() {
    const user = tg?.initDataUnsafe?.user;
    return (state.meName || [user?.first_name, user?.last_name].filter(Boolean).join(' ') || '').trim();
  }

  function renderHello() {
    const first = displayName().split(/\s+/)[0];
    $('#w-hello').textContent = first ? t('app.welcome.hello', first) : t('app.welcome.hello_noname');
    // «Habibi Bakery» — qalin, karamel rangda
    if ('app.welcome.sub' in state.dict) {
      $('#w-sub').innerHTML = esc(t('app.welcome.sub')).replace('{0}', '<b class="brand-strong">Habibi Bakery</b>');
    }
    fitTaglines();
  }

  /** Kutib olish sahifasidagi gaplar: har biri bitta qatorga sig'adigan qilib shriftni moslaydi. */
  function fitTaglines() {
    // Uchala gap bir xil o'lchamda: har birini moslab, eng kichik o'lchamni hammasiga beramiz
    const run = () => {
      const items = $$('.w-love .taglines li');
      items.forEach((li) => fitOneLine(li, 10));
      const size = Math.min(...items.map((li) => parseFloat(getComputedStyle(li).fontSize)));
      items.forEach((li) => { li.style.fontSize = size + 'px'; });
    };
    requestAnimationFrame(run);
    // Montserrat yuklangach o'lchamlar o'zgaradi — qayta moslaymiz
    document.fonts?.ready.then(() => requestAnimationFrame(run));
  }

  /** Sovg'a setlari kategoriyasi keng banner kartochkalar bilan ko'rsatiladi. */
  const isBannerCategory = (c) => c.emoji === '🎁';
  const allProducts = () => state.menu.flatMap((c) => c.products);
  const promoProducts = () => allProducts().filter((p) => p.discount > 0);
  const trendProducts = () => (state.trending || []).map((r) => findProduct(r.id)).filter(Boolean);
  const soldThisMonth = (id) => (state.trending || []).find((r) => r.id === id)?.count || 0;
  /** Eng ko'p like olganlar — mahalliy hisob (like bosilganda darhol yangilanadi). */
  const lovedProducts = () => allProducts().filter((p) => p.likes > 0).sort((a, b) => b.likes - a.likes).slice(0, 10);

  /** Hamma narsani qayta chizadi (til o'zgarganda, savat tozalanganda va h.k.). */
  function renderMenu() {
    renderHello();
    renderHero();
    renderCatalog();
    $('#closed-home').innerHTML = closedBanner();
    if (state.view === 'category' && state.currentCat != null) renderCategory(state.currentCat);
  }

  /** Bosh rasm: admin yuklagan rasm, bo'lmasa — mahsulot rasmlaridan kollaj. */
  function renderHero() {
    const box = $('#w-hero');
    if (state.config?.bannerUrl) {
      box.innerHTML = `<img src="${esc(state.config.bannerUrl)}" alt="">`;
      // rasm yuklanmasa — buzilgan belgi o'rniga kollaj
      box.querySelector('img').onerror = () => renderCollage(box);
      return;
    }
    renderCollage(box);
  }

  function renderCollage(box) {
    const photos = allProducts().filter((p) => p.photoUrl).slice(0, 4);
    box.innerHTML = photos.length
      ? `<div class="w-collage n${photos.length}">${photos.map((p) => `<img src="${esc(p.photoUrl)}" alt="" onerror="this.remove()">`).join('')}</div>`
      : '<div class="w-hero-empty">🧁</div>';
  }

  function renderCatalog() {
    const promos = promoProducts();
    const pill = (cls, key, emoji, title, count) => `
      <button class="sp-pill ${cls}" data-open="${key}">
        <span class="sp-emoji">${emoji}</span><span class="sp-title">${esc(title)}</span>${count ? `<span class="sp-count">${count}</span>` : ''}
      </button>`;
    const tile = (c) => {
      const photo = c.products.find((p) => p.photoUrl)?.photoUrl;
      return `
        <button class="cat-tile" data-open="${c.id}">
          <span class="ct-img">${c.isNew || c.products.some((p) => p.isNew) ? `<span class="d-new ct-new">${t('app.new_badge')}</span>` : ''}${photo ? `<img src="${esc(photo)}" alt="" onerror="this.remove()">` : esc(c.emoji || '🧁')}</span>
          <span class="ct-body"><b>${esc(c.name)}</b><small>${t('app.products_n', c.products.length)}</small></span>
        </button>`;
    };
    // Trend, Aksiya, Eng sevimli — tepada yonma-yon ingichka tabletkalar
    $('#cat-tiles').innerHTML =
      `<div class="sp-row${promos.length ? '' : ' two'}">`
      + pill('trend', 'trend', '🔥', t('app.trend'), '')
      + (promos.length ? pill('promo', 'promo', '🏷', t('app.promo_short'), promos.length) : '')
      + pill('loved', 'loved', '❤️', t('app.loved'), '')
      + '</div>'
      + state.menu.map(tile).join('');
  }

  /** Kategoriya (yoki «Eng mashhur», «Aksiyalar») mahsulotlari. */
  function renderCategory(key) {
    state.currentCat = key;
    let title; let list; let banner = false;
    let extra = null;   // kartochkadagi qo'shimcha qator
    if (key === 'trend') { title = '🔥 ' + t('app.trend'); list = trendProducts(); extra = 'sold'; }
    else if (key === 'loved') { title = '❤️ ' + t('app.loved'); list = lovedProducts(); extra = 'likes'; }
    else if (key === 'mine') { title = '❤️ ' + t('app.my_likes'); list = allProducts().filter((p) => state.liked.has(p.id)); }
    else if (key === 'promo') { title = '🏷 ' + t('app.promo'); list = promoProducts(); }
    else {
      const c = state.menu.find((x) => String(x.id) === String(key));
      if (!c) return;
      title = c.name; list = c.products; banner = isBannerCategory(c);
    }
    $('#category-title').textContent = title;
    state.cardExtra = extra;
    $('#category-hint').textContent = key === 'trend' ? t('app.trend_hint') : key === 'loved' ? t('app.loved_hint')
      : key === 'mine' ? t('app.my_likes_hint')
      : list.length ? t('app.products_n', list.length) : '';
    $('#catalog').innerHTML = !list.length
      ? `<div class="empty"><div class="big-emoji">${{ promo: '🏷', loved: '❤️', mine: '🤍' }[key] || '🧁'}</div><b>${{ promo: t('app.promo_empty'), loved: t('app.loved_empty'), mine: t('app.my_likes_empty') }[key] || t('app.menu_soon')}</b></div>`
      : banner
        ? `<div class="banner-list">${list.map(bannerCard).join('')}</div>`
        : `<div class="grid2">${list.map(productCard).join('')}</div>`;
  }

  function openCategory(key) {
    haptic();
    state.cardExtra = null;
    state.categoryTab = key === 'mine' ? 'profile' : 'menu';   // pastda qaysi tab yonib tursin
    renderCategory(key);
    go('category');
  }

  const HEART = '<svg viewBox="0 0 24 24" width="16" height="16"><path d="M12 20.5s-7.5-4.6-9.3-9.2C1.4 8 3.4 4.5 7 4.5c2 0 3.5 1.1 5 3 1.5-1.9 3-3 5-3 3.6 0 5.6 3.5 4.3 6.8-1.8 4.6-9.3 9.2-9.3 9.2z"/></svg>';

  function likeButton(p) {
    const on = state.liked.has(p.id);
    return `<button class="d-like${on ? ' on' : ''}" data-like="${p.id}" aria-label="like">${HEART}<span>${p.likes || ''}</span></button>`;
  }

  /** Trend/Sevimlilar bo'limida: «Bu oy 12 marta sotildi» yoki «24 ta like». */
  function cardExtraLine(p) {
    if (state.view !== 'category' && state.cardExtra == null) return '';
    if (state.cardExtra === 'sold') {
      const n = soldThisMonth(p.id);
      return n ? `<div class="d-extra">🔥 ${t('app.sold_n', n)}</div>` : '';
    }
    if (state.cardExtra === 'likes') return `<div class="d-extra">❤️ ${t('app.likes_n', p.likes)}</div>`;
    return '';
  }

  /** Kartochkadagi «⭐ 4.8 (12)» — baho bo'lsa. */
  function ratingLine(p) {
    return p.ratingCount > 0 ? `<div class="d-rate">⭐ ${p.rating.toFixed(1)} <i>(${p.ratingCount})</i></div>` : '';
  }

  /** Mahsulot oynasidagi baho: o'rtachasi va 5 ta yulduz (sotib olgan bo'lsa bosiladi). */
  function ratingBlock(p) {
    const mine = state.myRatings[p.id] || 0;
    const can = state.purchased.has(p.id);
    const stars = [1, 2, 3, 4, 5].map((n) =>
      `<button class="ps-star${n <= mine ? ' on' : ''}" data-rate="${n}" aria-label="${n}">★</button>`).join('');
    return `
      <div class="ps-rating">
        <div class="ps-rate-avg">${p.ratingCount > 0
          ? `<b>⭐ ${p.rating.toFixed(1)}</b><span>${t('app.ratings_n', p.ratingCount)}</span>`
          : `<span>${t('app.no_ratings')}</span>`}</div>
        <div class="ps-rate-mine${can ? '' : ' locked'}">
          <small>${can ? t('app.rate_title') : t('app.rate_hint')}</small>
          <div class="ps-stars">${stars}</div>
        </div>
      </div>`;
  }

  async function rateProduct(p, stars) {
    if (!state.purchased.has(p.id)) { toast(t('app.rate_hint')); notify('error'); return; }
    const prev = state.myRatings[p.id];
    state.myRatings[p.id] = stars;
    haptic();
    renderSheet();
    try {
      const r = await api(`/api/products/${p.id}/rating`, { method: 'POST', body: JSON.stringify({ stars }) });
      p.rating = r.avg;
      p.ratingCount = r.count;
      notify('success');
      toast(t('app.rate_thanks'));
      if (sheetState) renderSheet();
      refreshCard(p.id);
    } catch (e) {
      if (prev) state.myRatings[p.id] = prev; else delete state.myRatings[p.id];
      if (sheetState) renderSheet();
      toast(e.message);
    }
  }

  function productCard(p) {
    const qty = productQty(p);
    return `
      <div class="d-card${p.available ? '' : ' off'}" data-product="${p.id}" role="button" tabindex="0">
        <div class="d-badges">
          ${p.isNew ? `<span class="d-new">${t('app.new_badge')}</span>` : ''}
          ${p.discount > 0 ? `<span class="d-sale">-${p.discount}%</span>` : ''}
          ${qty ? `<span class="d-qty">${qty}</span>` : ''}
        </div>
        ${likeButton(p)}
        ${productImage(p, 'd-img')}
        <div class="d-name">${esc(p.name)}</div>
        ${ratingLine(p)}
        ${cardExtraLine(p)}
        <div class="d-row">
          <div class="d-price">${p.available ? priceLabel(p) : `<small>${t('app.sold_out')}</small>`}</div>
          <button class="d-add" data-add="${p.id}" aria-label="${t('app.add')}">+</button>
        </div>
      </div>`;
  }

  /** Keng banner (Sovg'a setlari): chapda nom, tavsif, narx va tugma; o'ngda rasm. */
  function bannerCard(p) {
    const qty = productQty(p);
    return `
      <div class="b-card${p.available ? '' : ' off'}" data-product="${p.id}" role="button" tabindex="0">
        ${likeButton(p)}
        ${p.isNew ? `<span class="d-new b-new">${t('app.new_badge')}</span>` : ''}
        <div>
          <div class="b-title">${esc(p.name)}</div>
          ${cardExtraLine(p)}
          ${p.description ? `<p class="b-desc">${esc(p.description)}</p>` : ''}
          <div class="b-price">${p.available ? priceLabel(p) : t('app.sold_out')}</div>
          <button class="b-btn" data-add="${p.id}">${t('app.add')}${qty ? `<span class="b-qty">${qty}</span>` : ''}</button>
        </div>
        ${productImage(p, 'b-img')}
      </div>`;
  }

  function refreshCard(productId) {
    const p = findProduct(productId);
    const el = document.querySelector(`[data-product="${productId}"]`);
    if (p && el) el.outerHTML = el.classList.contains('b-card') ? bannerCard(p) : productCard(p);
  }

  function findProduct(id) {
    for (const c of state.menu) for (const p of c.products) if (p.id === id) return p;
    return null;
  }

  /** Like: darhol ko'rsatamiz, keyin serverga yuboramiz (xato bo'lsa qaytaramiz). */
  async function toggleLike(product) {
    const was = state.liked.has(product.id);
    const apply = (liked, count) => {
      liked ? state.liked.add(product.id) : state.liked.delete(product.id);
      product.likes = count;
      refreshCard(product.id);
      if (state.view === 'cart') renderCart();
      if (state.view === 'category' && state.currentCat === 'mine') renderCategory('mine');
      $('#my-likes-count').textContent = state.liked.size;
    };
    apply(!was, Math.max(0, (product.likes || 0) + (was ? -1 : 1)));
    haptic(was ? 'light' : 'medium');
    try {
      const r = await api(`/api/products/${product.id}/like`, { method: 'POST' });
      apply(r.liked, r.count);
    } catch (e) {
      apply(was, Math.max(0, (product.likes || 0) + (was ? 1 : -1)));
      toast(e.message);
    }
  }

  /** Kartochkadagi «+»: bitta o'lchamli bo'lsa darhol savatga, aks holda o'lcham tanlash oynasi. */
  function quickAdd(product) {
    if (!product.available) { toast(t('app.sold_out_toast')); return; }
    if (product.variants.length > 1) { openSheet(product); return; }
    const v = product.variants[0];
    setQty(v.id, cartQty(v.id) + 1);
    notify('success');
    toast(t('app.added', product.name));
    refreshCard(product.id);
  }

  // ---- Profil va Aloqa
  function renderProfile() {
    const user = tg?.initDataUnsafe?.user;
    $('#my-likes-count').textContent = state.liked.size;
    const name = displayName();
    const first = name.split(/\s+/)[0];
    $('#profile-name').textContent = first ? t('app.hello', first) : t('app.guest');
    requestAnimationFrame(() => fitOneLine($('#profile-name'), 15));
    const uname = $('#profile-user');
    uname.textContent = user?.username ? '@' + user.username : '';
    uname.hidden = !user?.username;
    $('#profile-phone').textContent = state.mePhone ? '+998 ' + formatPhone(state.mePhone) : '';
    const av = $('#profile-avatar');
    if (user?.photo_url) av.innerHTML = `<img src="${esc(user.photo_url)}" alt="" onerror="this.remove()">`;
    else av.textContent = (name || 'H')[0].toUpperCase();
  }

  /** Qisqa summa: 300 000 → «300 ming», 1 200 000 → «1 mln 200 ming» (ruschada «тыс», «млн»). */
  function shortMoney(sum) {
    const th = state.dict['app.num.thousand'] || (state.lang === 'ru' ? 'тыс' : 'ming');
    const mln = state.dict['app.num.million'] || (state.lang === 'ru' ? 'млн' : 'mln');
    const millions = Math.floor(sum / 1_000_000);
    const thousands = Math.floor((sum % 1_000_000) / 1000);
    const parts = [];
    if (millions) parts.push(`${millions} ${mln}`);
    if (thousands) parts.push(`${thousands} ${th}`);
    return parts.join(' ') || (sum > 0 ? num(sum) : '0');
  }

  /** Matn bir qatorga sig'maguncha shriftni biroz kichraytiradi. */
  function fitOneLine(el, min = 12) {
    el.style.fontSize = '';
    let size = parseFloat(getComputedStyle(el).fontSize);
    while (el.scrollWidth > el.clientWidth && size > min) {
      size -= 0.5;
      el.style.fontSize = size + 'px';
    }
  }

  /** Profil statistikasi: buyurtmalar soni va jami xarid (bekor qilinganlarsiz). */
  async function loadProfileStats() {
    if (!inTelegram && !state.config) return;
    try {
      const orders = await api('/api/orders/my?lang=' + state.lang);
      const active = orders.filter((o) => o.status !== 'CANCELLED');
      $('#orders-total').textContent = t('app.orders_total', active.length);
      // to'lovi kutilayotganlar — qizil bo'lib ko'rinsin
      const pending = orders.filter((o) => o.group === 'PENDING').length;
      $('#orders-pending').hidden = !pending;
      $('#orders-pending').textContent = pending ? t('app.pending_n', pending) : '';
      $('#orders-pending-count').textContent = pending || '';
      $('#btn-my-orders').classList.toggle('has-pending', !!pending);
      loadAddresses().then(() => { $('#addresses-total').textContent = t('app.addresses_n', state.addresses.length); });
      $('#stat-spent').textContent = shortMoney(active.reduce((s, o) => s + o.total, 0));
      fitOneLine($('#stat-spent'));
    } catch (_) {
      $('#stat-spent').textContent = '0';
      $('#orders-total').textContent = t('app.orders_total', 0);
    }
  }

  function openAbout() {
    $('#about-sheet').hidden = false;
    $('#about-backdrop').hidden = false;
    syncBackButton();
    haptic();
  }
  function closeAbout() {
    $('#about-sheet').hidden = true;
    $('#about-backdrop').hidden = true;
    syncBackButton();
  }

  /** Buyurtma shartlari — botdagi matnning o'zi (bot.terms). */
  function openTerms() {
    const raw = t('bot.terms');
    // matn bizniki (i18n): faqat <b> teglari bor — qolganini xavfsiz qilib, <b> ni qaytaramiz
    $('#terms-body').innerHTML = esc(raw).replace(/&lt;(\/?)b&gt;/g, '<$1b>').replace(/\n/g, '<br>');
    $('#terms-sheet').hidden = false;
    $('#terms-backdrop').hidden = false;
    syncBackButton();
    haptic();
  }
  function closeTerms() {
    $('#terms-sheet').hidden = true;
    $('#terms-backdrop').hidden = true;
    syncBackButton();
  }

  /** Ish vaqti: hozir buyurtma qabul qilinadimi (server aytgan holat). */
  const isClosed = () => !!state.config && (state.config.paused || state.config.openNow === false);
  function closedBanner() {
    if (!isClosed()) return '';
    const c = state.config;
    if (c.paused) return `<div class="closed-banner"><b>${t('app.paused_title')}</b><span>${t('app.paused_text')}</span></div>`;
    return `<div class="closed-banner"><b>${t('app.closed_title')}</b><span>${t('app.closed_text', c.openTime, c.closeTime)}</span></div>`;
  }

  /** Savatda tort bormi — bo'lsa «Tortga yozuv» maydoni chiqadi. */
  function cartHasCake() {
    // 🎂 kategoriya (Desertlar: Butun/Yarim/Chorak) — nomi o'zgarsa ham
    const cakeCats = new Set((state.menu || []).filter((c) => c.emoji === '🎂' || /tort|торт/i.test(c.name)).map((c) => c.id));
    return state.cart.some(({ variantId }) => {
      const pid = state.variants.get(variantId)?.product.id;
      return (state.menu || []).some((c) => cakeCats.has(c.id) && c.products.some((p) => p.id === pid));
    });
  }

  /** Matnni nusxalash: clipboard bo'lmasa (eski WebView) — yashirin maydon orqali. */
  function copyText(text, okMsg) {
    const fallback = () => {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.cssText = 'position:fixed;top:-1000px;opacity:0';
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand('copy'); } catch (_) { ok = false; }
      ta.remove();
      toast(ok ? okMsg : text);
    };
    haptic();
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(text).then(() => toast(okMsg), fallback);
    else fallback();
  }

  function renderContact() {
    const c = state.config || {};
    const row = (ico, title, value, href, data) => `
      <${href ? 'a' : 'div'} class="menu-row contact-row" ${href ? `href="${esc(href)}" data-link="${esc(data || href)}"` : ''}>
        <span class="menu-ico">${ico}</span>
        <span class="menu-title">${esc(title)}<small>${esc(value)}</small></span>
        ${href && !href.startsWith('tel:') ? '<span class="chevron">›</span>' : ''}
      </${href ? 'a' : 'div'}>`;
    const phoneDigits = (c.phone || '').replace(/[^\d+]/g, '');
    $('#contact-list').innerHTML = [
      c.phone ? row('☎️', t('app.contact.phone'), c.phone, 'tel:' + phoneDigits)
        .replace('</a>', `<button type="button" class="copy-btn" data-copy="${esc(phoneDigits)}">📋 ${t('app.copy')}</button></a>`) : '',
      c.telegram ? row('✈️', t('app.contact.telegram'), '@' + c.telegram, 'https://t.me/' + c.telegram) : '',
      c.groupUrl ? row('👥', t('app.contact.group'), t('app.join'), c.groupUrl) : '',
      c.instagram ? row('📸', t('app.contact.instagram'), '@' + c.instagram, 'https://www.instagram.com/' + c.instagram + '/') : '',
    ].join('');
  }

  function openContact() {
    renderContact();
    $('#contact-sheet').hidden = false;
    $('#contact-backdrop').hidden = false;
    syncBackButton();
    haptic();
  }
  function closeContact() {
    $('#contact-sheet').hidden = true;
    $('#contact-backdrop').hidden = true;
    syncBackButton();
  }

  /** Havolalarni Telegram ichida to'g'ri ochish (t.me — Telegram'da, boshqalari — brauzerda). */
  function openExternal(url) {
    if (url.startsWith('tel:')) { location.href = url; return; }
    try {
      if (url.startsWith('https://t.me/') && tg?.openTelegramLink) return tg.openTelegramLink(url);
      if (tg?.openLink) return tg.openLink(url);
    } catch (_) { /* oddiy havola sifatida */ }
    window.open(url, '_blank');
  }

  // ------------------------------------------------------------ mahsulot oynasi
  let sheetState = null;
  function openSheet(product) {
    if (!product.available) { toast(t('app.sold_out_toast')); return; }
    const first = product.variants.find((v) => cartQty(v.id)) || product.variants[0];
    sheetState = { product, variantId: first.id, qty: 1 };
    renderSheet();
    $('#sheet').hidden = false;
    $('#sheet').scrollTop = 0;
    $('#sheet-backdrop').hidden = false;
    tg?.BackButton.show();
    haptic();
  }

  /** Setlar (🎁 kategoriya) uchun eski oyna qoladi — ularga alohida dizayn qilinadi. */
  const isSetProduct = (p) => state.menu.some((c) => isBannerCategory(c) && c.products.some((x) => x.id === p.id));

  function renderSheet() {
    const { product: p, variantId, qty } = sheetState;
    const v = p.variants.find((x) => x.id === variantId);
    const inCart = productQty(p);
    const isSet = isSetProduct(p);
    $('#sheet').classList.toggle('ps', true);
    $('#sheet').classList.toggle('ps-set', isSet);
    const tags = (p.isNew ? `<span class="ps-tag new">${t('app.new_badge')}</span>` : '')
      + (isSet ? `<span class="ps-tag gift">${t('app.set_tag')}</span>` : '')
      + (p.discount > 0 ? `<span class="ps-tag sale">-${p.discount}%</span>` : '');
    $('#sheet-body').innerHTML = `
      <div class="ps-media">${productImage(p, 'ps-img')}${likeButton(p)}${tags ? `<div class="ps-tags">${tags}</div>` : ''}</div>
      <div class="ps-head">
        <h2 class="ps-name">${esc(p.name)}</h2>
        <div class="ps-price">${v.oldPrice ? `<s>${num(v.oldPrice * qty)}</s><span class="p-new">${money(v.price * qty)}</span>` : money(v.price * qty)}${p.perPiece && qty === 1 ? ` <small class="per-unit">/ ${t('app.per_piece')}</small>` : ''}</div>
      </div>
      ${v.oldPrice ? `<div class="ps-save">💰 ${t('app.you_save', money((v.oldPrice - v.price) * qty))}</div>` : ''}
      ${ratingBlock(p)}
      ${p.description ? `<p class="ps-desc">${esc(p.description)}</p>` : ''}
      ${isSet && p.contents ? `
        <h4 class="ps-label">🎁 ${t('app.set_contents')}</h4>
        <ul class="ps-contents">${p.contents.split('\n').filter((l) => l.trim()).map((l) => `<li>${esc(l.trim())}</li>`).join('')}</ul>` : ''}
      ${!isSet && p.variants.length > 1 ? `
        <h4 class="ps-label">${isSizeList(p) ? t('app.size') : t('app.flavor')}</h4>
        <div class="ps-variants">${p.variants.map((x) => `
          <button class="ps-var${x.id === variantId ? ' on' : ''}" data-variant="${x.id}">${esc(x.name || t('app.standard'))}</button>`).join('')}
        </div>` : ''}
      ${inCart ? `<p class="ps-note">${t('app.in_cart_note', inCart)}</p>` : ''}
      <div class="ps-actions">
        <div class="ps-stepper"><button data-sq="-1" aria-label="−">−</button><span>${qty}</span><button data-sq="1" aria-label="+">+</button></div>
        <button class="ps-add" id="sheet-add">${t('app.add_to_cart')}</button>
      </div>`;
  }

  function closeSheet() {
    $('#sheet').hidden = true;
    $('#sheet-backdrop').hidden = true;
    sheetState = null;
    syncBackButton();
  }

  // ------------------------------------------------------------ savat sahifasi
  function renderCart() {
    if (!state.cart.length) {
      $('#cart-list').innerHTML = `
        <div class="empty cart-empty"><div class="big-emoji">🛍️</div><b>${t('app.cart_empty')}</b>${t('app.cart_empty_hint')}
          <button class="w-cta" data-browse>${t('app.cart.browse')} →</button></div>`;
      $('#cart-summary').innerHTML = '';
      $('#btn-clear').hidden = true;
      return;
    }
    $('#btn-clear').hidden = false;
    $('#cart-list').innerHTML = state.cart.map(({ variantId, qty }) => {
      const { variant: v, product: p } = state.variants.get(variantId);
      const meta = [v.name, p.description].filter(Boolean).join(' · ');
      return `
        <div class="ci-card" data-cart-product="${p.id}">
          ${likeButton(p)}
          ${productImage(p, 'ci-img')}
          <div class="ci-body">
            <div class="ci-name">${esc(p.name)}</div>
            ${meta ? `<div class="ci-meta">${esc(meta)}</div>` : ''}
            <div class="ci-price">${v.oldPrice ? `<s>${num(v.oldPrice * qty)}</s><span class="p-new">${money(v.price * qty)}</span>` : money(v.price * qty)}</div>
            ${v.oldPrice ? `<div class="ci-save">💰 ${t('app.you_save', money((v.oldPrice - v.price) * qty))}</div>` : ''}
            <div class="ci-row">
              <div class="ci-stepper">
                <button data-cq="-1" data-v="${variantId}" aria-label="−">−</button>
                <span>${qty}</span>
                <button data-cq="1" data-v="${variantId}" aria-label="+">+</button>
              </div>
              <button class="ci-remove" data-rm="${variantId}">${t('app.remove')}</button>
            </div>
          </div>
        </div>`;
    }).join('') + `<button class="ci-more" data-browse>＋ ${t('app.cart.add_more')}</button>`;
    $('#cart-summary').innerHTML = closedBanner() + `
      <div class="cs-card">
        ${cartSavings() > 0
          ? `<div class="cs-row"><span>${t('app.cart.subtotal')} (${cartCount()})</span><span>${money(cartTotal() + cartSavings())}</span></div>
             <div class="cs-row cs-discount"><span>${t('app.cart.discount')}</span><span>−${money(cartSavings())}</span></div>`
          : `<div class="cs-row"><span>${t('app.cart.subtotal')} (${cartCount()})</span><span>${money(cartTotal())}</span></div>`}
        <div class="cs-note">${t('app.cart.delivery_note')}</div>
        <div class="cs-line"></div>
        <div class="cs-row total"><span>${t('app.total')}</span><span>${money(cartTotal())}</span></div>
        ${cartSavings() > 0 ? `<div class="cs-saved">🎉 ${t('app.cart.saved', money(cartSavings()))}</div>` : ''}
        ${isClosed()
          ? `<button class="cs-btn" disabled>${state.config.paused ? t('app.paused_btn') : t('app.closed_btn', state.config.openTime)}</button>`
          : `<button class="cs-btn" id="cart-checkout">${t('app.checkout')}</button>`}
      </div>`;
  }

  // ------------------------------------------------------------ rasmiylashtirish
  function renderCheckout() {
    const c = state.checkout;
    c.deliveryType = 'DELIVERY';          // hozircha faqat yetkazib berish
    if (c.paymentMethod !== 'CASH') c.paymentMethod = 'CARD_TRANSFER';   // karta yoki naqd
    $$('.ck-pay[data-pay]').forEach((b) => b.classList.toggle('on', b.dataset.pay === c.paymentMethod));
    const saved = storage('hb_contact') || {};
    const user = tg?.initDataUnsafe?.user;
    if (!$('#name').value) $('#name').value = saved.name || state.meName || [user?.first_name, user?.last_name].filter(Boolean).join(' ');
    if (!$('#phone').value && (saved.phone || state.mePhone)) $('#phone').value = formatPhone(saved.phone || state.mePhone);
    if (!$('#address').value && saved.address) $('#address').value = saved.address;
    if (!c.location && saved.location) c.location = saved.location;

    $('#inscription-block').hidden = !cartHasCake();

    renderSavedAddrChips();
    if (state.addresses == null) loadAddresses().then(() => state.view === 'checkout' && renderSavedAddrChips());

    // Xarita qatori (majburiy)
    $('#btn-map').classList.toggle('ok', !!c.location);
    if (c.location) {
      $('#map-title').textContent = '✓ ' + t('app.map_picked');
      $('#map-sub').textContent = `${c.location.label || t('app.map_point')} · ${t('app.map_change')}`;
    } else {
      $('#map-title').textContent = t('app.map_pick');
      $('#map-sub').textContent = t('app.map_pick_hint');
    }

    $('#checkout-summary').innerHTML = `
      <div class="cs-card">
        <div class="ck-title"><span>${t('app.ck.summary')}</span></div>
        ${state.cart.map(({ variantId, qty }) => {
          const { variant: v, product: p } = state.variants.get(variantId);
          return `<div class="ck-line"><span>${esc(p.name)}${v.name ? ' · ' + esc(v.name) : ''} <i>× ${qty}</i></span><span>${money(v.price * qty)}</span></div>`;
        }).join('')}
        <div class="cs-line"></div>
        ${cartSavings() > 0 ? `<div class="cs-row cs-discount"><span>${t('app.cart.discount')}</span><span>−${money(cartSavings())}</span></div>` : ''}
        <div class="cs-row"><span>${t('app.cart.delivery')}</span><span>${t('app.cart.delivery_val')}</span></div>
        <div class="cs-row total"><span>${t('app.total')}</span><span>${money(cartTotal())}</span></div>
        ${cartSavings() > 0 ? `<div class="cs-saved">🎉 ${t('app.cart.saved', money(cartSavings()))}</div>` : ''}
        <p class="cs-note">${t('app.cart.delivery_note')}</p>
        <button type="button" class="cs-btn" id="checkout-submit" ${state.submitting ? 'disabled' : ''}>${state.submitting ? t('app.sending') : t('app.place_order')}</button>
      </div>`;
  }

  function formatPhone(raw) {
    const d = raw.replace(/\D/g, '').replace(/^998(?=\d{9})/, '').slice(0, 9);
    return [d.slice(0, 2), d.slice(2, 5), d.slice(5, 7), d.slice(7, 9)].filter(Boolean).join(' ');
  }

  function fail(el, msg) {
    el.classList.remove('invalid');
    void el.offsetWidth;
    el.classList.add('invalid');
    const y = el.getBoundingClientRect().top + window.scrollY - window.innerHeight / 3;
    window.scrollTo({ top: Math.max(0, y), behavior: 'smooth' });
    toast(msg);
    notify('error');
    return false;
  }

  function validate() {
    const c = state.checkout;
    // Faqat ko'cha nomi yoki izoh bilan qabul qilinmaydi — xaritadagi nuqta majburiy
    if (!c.location) return fail($('#btn-map'), t('app.err.location'));
    if ($('#name').value.trim().length < 2) return fail($('#name'), t('app.err.name'));
    if ($('#phone').value.replace(/\D/g, '').length !== 9) return fail($('#phone').closest('.ck-phone'), t('app.err.phone'));
    return true;
  }

  async function submitOrder() {
    if (state.submitting || !validate()) return;
    const c = state.checkout;
    const delivery = c.deliveryType === 'DELIVERY';
    const payload = {
      items: state.cart.map((i) => ({ variantId: i.variantId, quantity: i.qty })),
      customerName: $('#name').value.trim(),
      phone: '+998 ' + $('#phone').value.trim(),
      deliveryType: c.deliveryType,
      address: delivery ? $('#address').value.trim() : null,
      latitude: delivery && c.location ? c.location.lat : null,
      longitude: delivery && c.location ? c.location.lng : null,
      deliveryDate: null,     // sana/vaqt hozircha tanlanmaydi
      deliveryTime: null,
      paymentMethod: c.paymentMethod,
      comment: $('#comment').value.trim() || null,
      language: state.lang,
      inscription: cartHasCake() ? ($('#inscription').value.trim() || null) : null,
    };
    state.submitting = true;
    renderCheckout();
    try {
      const order = await api('/api/orders', { method: 'POST', body: JSON.stringify(payload) });
      storage('hb_contact', { name: payload.customerName, phone: $('#phone').value.trim(), address: $('#address').value.trim(), location: c.location });
      state.cart = [];
      saveCart();
      $('#comment').value = '';
      notify('success');
      state.lastOrder = order;
      renderSuccess(order);
      state.history = [];
      go('success', { push: false });
      window.scrollTo(0, 0);
      renderMenu();
    } catch (e) {
      toast(e.message);
      notify('error');
    } finally {
      state.submitting = false;
      if (state.view === 'checkout') renderCheckout();
    }
  }

  function renderSuccess(order) {
    if (order.cancelled) return renderCancelled(order);
    $('.ok-hero h2').textContent = t('app.thanks');
    $('.ok-hero .check-circle').classList.remove('x');
    $('.ok-hero .check-circle path').setAttribute('d', 'M15 27l7 7 15-15');
    $('#success-text').innerHTML = t('app.success_text', order.id);
    $('#cancel-box').innerHTML = !uploaded.has(order.id) && order.cancelUntil > Date.now()
      ? cancelBlock(order.id, order.cancelUntil, true) : '';
    const p = order.payment;
    const step = (n, html) => `<div class="ok-step"><span class="num">${n}</span><span>${html}</span></div>`;
    if (p) {
      $('#payment-box').innerHTML = `
        <div class="pay-box">
          ${p.cardNumber ? `
          <div class="wallet-card">
            <small>${t('app.card_for_payment')}</small>
            <div class="card-number">${esc(p.cardNumber)}</div>
            <div class="line"><span>${esc(p.cardHolder || '')}</span><button class="copy" id="btn-copy">${t('app.copy')}</button></div>
          </div>` : ''}
          <div class="ok-steps">
            ${step(1, t(p.cardNumber ? 'app.step.transfer' : 'app.step.transfer_operator', money(p.amount)))}
            ${step(2, t('app.step.receipt'))}
            ${step(3, t('app.step.confirm'))}
          </div>
          ${uploadBlock(order.id)}
        </div>`;
      $('#btn-copy')?.addEventListener('click', () => copyText(p.cardNumber.replace(/\s/g, ''), t('app.copied')));
    } else {
      $('#payment-box').innerHTML = `<div class="pay-box"><div class="ok-steps">
        ${step(1, t('app.step.cash_confirm'))}
        ${step(2, t('app.step.cash_pay'))}</div></div>`;
    }
  }

  // ------------------------------------------------------------ 5 daqiqa ichida bekor qilish
  const fmtLeft = (ms) => { const sec = Math.max(0, Math.ceil(ms / 1000)); return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`; };

  function cancelBlock(orderId, until, withHint) {
    return `<div class="cancel-wrap" data-order="${orderId}">
      <button class="cancel-btn" data-cancel="${orderId}" data-until="${until}">${t('app.cancel_order', fmtLeft(until - Date.now()))}</button>
      ${withHint ? `<p class="cancel-hint">${t('app.cancel_window_hint')}</p>` : ''}
    </div>`;
  }

  // Har soniyada qolgan vaqtni yangilaydi; tugasa tugma yo'qoladi (server ham vaqtni tekshiradi)
  setInterval(() => {
    $$('[data-until]').forEach((b) => {
      const left = Number(b.dataset.until) - Date.now();
      if (left <= 0) b.closest('.cancel-wrap')?.remove();
      else b.textContent = t('app.cancel_order', fmtLeft(left));
    });
  }, 1000);

  function renderCancelled(order) {
    $('.ok-hero .check-circle').classList.add('x');
    $('.ok-hero .check-circle path').setAttribute('d', 'M18 18l16 16M34 18L18 34');
    $('.ok-hero h2').textContent = t('app.order_cancelled');
    $('#success-text').innerHTML = `#${order.id} · ${t('app.order_cancelled_hint')}`;
    $('#cancel-box').innerHTML = '';
    $('#payment-box').innerHTML = '';
  }

  async function cancelOrder(orderId, btn) {
    const run = async () => {
      btn.disabled = true;
      try {
        await api(`/api/orders/${orderId}/cancel`, { method: 'POST' });
        notify('success');
        toast(t('app.order_cancelled'));
        if (state.lastOrder?.id === orderId) state.lastOrder.cancelled = true;
        if (state.view === 'success' && state.lastOrder?.id === orderId) renderCancelled(state.lastOrder);
        if (state.view === 'orders') renderOrders();
        loadProfileStats();
      } catch (e) {
        notify('error');
        toast(e.message);
        btn.disabled = false;
      }
    };
    haptic('medium');
    // Telegram'ning o'z tasdiqlash oynasi (bo'lmasa — darhol)
    if (tg?.showConfirm && tg.isVersionAtLeast?.('6.2')) tg.showConfirm(t('app.cancel_confirm'), (ok) => ok && run());
    else run();
  }

  document.addEventListener('click', (e) => {
    const pay = e.target.closest('.ck-pay[data-pay]');
    if (pay) {
      state.checkout.paymentMethod = pay.dataset.pay;
      $$('.ck-pay[data-pay]').forEach((b) => b.classList.toggle('on', b === pay));
      haptic();
      return;
    }
    const b = e.target.closest('[data-cancel]');
    if (b && !b.disabled) cancelOrder(Number(b.dataset.cancel), b);
  });

  // ------------------------------------------------------------ chek yuklash
  const uploaded = new Set();   // shu sessiyada chek yuklangan buyurtmalar
  let uploadTarget = null;

  function uploadBlock(orderId) {
    return uploaded.has(orderId)
      ? `<div class="upload-done">${t('app.receipt_uploaded')}</div>`
      : `<button class="cs-btn upload-btn" data-upload="${orderId}">${t('app.upload_receipt')}</button>`;
  }

  /** Rasmni 1600px gacha kichraytirib JPEG qiladi (sekin internetda tez yuklanishi uchun). */
  function shrinkImage(file, maxSide = 1600) {
    return new Promise((resolve) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => {
        const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
        URL.revokeObjectURL(url);
        canvas.toBlob((blob) => resolve(blob || file), 'image/jpeg', 0.85);
      };
      img.onerror = () => { URL.revokeObjectURL(url); resolve(file); };
      img.src = url;
    });
  }

  async function uploadReceipt(orderId, file) {
    const buttons = $$(`[data-upload="${orderId}"]`);
    buttons.forEach((b) => { b.disabled = true; b.textContent = t('app.uploading'); });
    try {
      const blob = await shrinkImage(file);
      const form = new FormData();
      form.append('file', blob, `chek-${orderId}.jpg`);
      const res = await fetch(`/api/orders/${orderId}/receipt`, {
        method: 'POST',
        headers: { 'X-Telegram-Init-Data': tg?.initData || '', 'X-Lang': state.lang },
        body: form,
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.error || t('app.receipt_failed'));
      uploaded.add(orderId);
      $$(`.cancel-wrap[data-order="${orderId}"]`).forEach((el) => el.remove());   // chek yuborildi — endi bekor qilib bo'lmaydi
      notify('success');
      toast(t('app.receipt_uploaded'));
      if (state.view === 'success' && state.lastOrder?.id === orderId) renderSuccess(state.lastOrder);
      if (state.view === 'orders') renderOrders();
    } catch (e) {
      notify('error');
      toast(e.message || t('app.receipt_failed'));
      buttons.forEach((b) => { b.disabled = false; b.textContent = t('app.upload_receipt'); });
    }
  }

  // ------------------------------------------------------------ mening manzillarim
  const ADDR_PRESETS = () => [t('app.addr_home'), t('app.addr_work')];

  async function loadAddresses() {
    if (!inTelegram && !state.config) { state.addresses = []; return; }
    try { state.addresses = await api('/api/me/addresses'); } catch (_) { state.addresses = state.addresses || []; }
  }

  const addrIcon = (label) => (/uy|уй|дом/i.test(label) ? '🏠' : /ish|iş|иш|работ|ofis|офис/i.test(label) ? '🏢' : '📍');

  async function renderAddresses() {
    const list = $('#addresses-list');
    if (state.addresses == null) {
      list.innerHTML = '<div class="skeleton" style="height:84px;margin:8px 0 12px"></div>';
      await loadAddresses();
    }
    const all = state.addresses || [];
    const cards = all.map((a) => `
      <button class="addr-card" data-addr="${a.id}">
        <span class="addr-ico">${addrIcon(a.label)}</span>
        <span class="addr-text"><b>${esc(a.label)}</b><small>${esc(a.address || a.mapLabel || `${a.latitude.toFixed(5)}, ${a.longitude.toFixed(5)}`)}</small></span>
        <span class="addr-edit">✏️</span>
      </button>`).join('');
    const add = all.length < 3
      ? `<button class="addr-add" data-addr-new>＋ ${t('app.addr_new')}</button>`
      : `<p class="addr-max">${t('app.addr_max')}</p>`;
    list.innerHTML = all.length
      ? cards + add
      : `<div class="empty"><div class="big-emoji">📍</div><b>${t('app.addr_empty')}</b>${t('app.addr_empty_hint')}</div>` + add;
  }

  function renderAddressEdit() {
    const a = state.editAddr || { id: null, label: '', location: null, address: '' };
    state.editAddr = a;
    $('#addr-title').textContent = a.id ? t('app.addr_edit') : t('app.addr_new');
    $('#addr-presets').innerHTML = ADDR_PRESETS().map((p) =>
      `<button type="button" class="addr-preset${a.label === p ? ' on' : ''}" data-preset="${esc(p)}">${addrIcon(p)} ${esc(p)}</button>`).join('');
    $('#addr-name').value = a.label || '';
    $('#addr-street').value = a.address || '';
    $('#addr-map').classList.toggle('ok', !!a.location);
    $('#addr-map-title').textContent = a.location ? '✓ ' + t('app.map_picked') : t('app.map_pick');
    $('#addr-map-sub').textContent = a.location ? `${a.location.label || t('app.map_point')} · ${t('app.map_change')}` : t('app.map_pick_hint');
    $('#addr-delete').hidden = !a.id;
  }

  async function saveAddress() {
    const a = state.editAddr;
    const label = $('#addr-name').value.trim();
    if (!label) return fail($('#addr-name'), t('app.err.addr_label'));
    if (!a.location) return fail($('#addr-map'), t('app.err.location'));
    const body = JSON.stringify({ label, latitude: a.location.lat, longitude: a.location.lng, mapLabel: a.location.label || null,
      address: $('#addr-street').value.trim() || null });
    try {
      await api(a.id ? `/api/me/addresses/${a.id}` : '/api/me/addresses', { method: a.id ? 'PUT' : 'POST', body });
      await loadAddresses();
      notify('success');
      toast(t('app.addr_saved'));
      back();
    } catch (e) { toast(e.message); notify('error'); }
  }

  function deleteAddress() {
    const a = state.editAddr;
    if (!a?.id) return;
    const go2 = async () => {
      try {
        await api(`/api/me/addresses/${a.id}`, { method: 'DELETE' });
        await loadAddresses();
        notify('success');
        back();
      } catch (e) { toast(e.message); }
    };
    if (tg?.showConfirm) tg.showConfirm(t('app.addr_delete_confirm'), (ok) => { if (ok) go2(); });
    else go2();
  }

  /** Rasmiylashtirishda saqlangan manzillar — bir bosishda tanlash. */
  function renderSavedAddrChips() {
    const box = $('#saved-addrs');
    const all = state.addresses || [];
    box.hidden = !all.length;
    if (!all.length) return;
    const c = state.checkout;
    const isOn = (a) => c.savedId === a.id
      || (c.location && Math.abs(c.location.lat - a.latitude) < 1e-6 && Math.abs(c.location.lng - a.longitude) < 1e-6);
    box.innerHTML = all.map((a) =>
      `<button type="button" class="addr-chip${isOn(a) ? ' on' : ''}" data-use-addr="${a.id}">${addrIcon(a.label)} ${esc(a.label)}</button>`).join('');
  }

  // ------------------------------------------------------------ xarita
  let map = null;
  let mapCenter = null;
  let mapTarget = 'checkout';   // 'checkout' — buyurtma manzili, 'addr' — saqlanadigan manzil
  let geocodeTimer = null;
  let geocodeSeq = 0;

  function loadLeaflet() {
    if (window.L) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const css = document.createElement('link');
      css.rel = 'stylesheet';
      css.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
      document.head.appendChild(css);
      const js = document.createElement('script');
      js.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
      js.onload = resolve;
      js.onerror = () => reject(new Error(t('app.map_load_error')));
      document.head.appendChild(js);
    });
  }

  async function openMap() {
    try {
      await loadLeaflet();
    } catch (e) {
      toast(e.message);
      return back();
    }
    const saved = mapTarget === 'addr' ? state.editAddr?.location : state.checkout.location;
    const start = saved || TASHKENT;
    if (!map) {
      map = L.map('map', { zoomControl: false, attributionControl: true }).setView([start.lat, start.lng], saved ? 17 : 12);
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '© OpenStreetMap',
      }).addTo(map);
      map.on('movestart', () => $('.map-pin').classList.add('lift'));
      map.on('moveend', () => {
        $('.map-pin').classList.remove('lift');
        const c = map.getCenter();
        mapCenter = { lat: +c.lat.toFixed(6), lng: +c.lng.toFixed(6) };
        $('#map-address').textContent = t('app.map_detecting');
        clearTimeout(geocodeTimer);
        geocodeTimer = setTimeout(() => reverseGeocode(mapCenter), 450);
      });
    } else if (saved) {
      map.setView([saved.lat, saved.lng], 17, { animate: false });
    }
    setTimeout(() => {
      map.invalidateSize();
      const c = map.getCenter();
      mapCenter = { lat: +c.lat.toFixed(6), lng: +c.lng.toFixed(6) };
      if (saved) $('#map-address').textContent = saved.label || t('app.map_point');
      else locateMe(true);
    }, 60);
  }

  async function reverseGeocode(point) {
    const seq = ++geocodeSeq;
    const accept = state.lang === 'ru' ? 'ru' : 'uz';
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${point.lat}&lon=${point.lng}&zoom=18&addressdetails=1&accept-language=${accept}`;
      const data = await fetch(url).then((r) => r.json());
      if (seq !== geocodeSeq) return;
      const a = data.address || {};
      const street = [a.road || a.pedestrian || a.neighbourhood, a.house_number].filter(Boolean).join(', ');
      const area = a.suburb || a.city_district || a.quarter || a.city || a.town || '';
      point.label = [street, area].filter(Boolean).join(' · ') || data.display_name?.split(',').slice(0, 2).join(',') || '';
    } catch (_) {
      point.label = '';
    }
    if (seq === geocodeSeq) {
      $('#map-address').textContent = point.label || `${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}`;
    }
  }

  /** Telegram LocationManager (Bot API 8.0+) yoki brauzer geolokatsiyasi. */
  function getLocation() {
    return new Promise((resolve, reject) => {
      const fallback = () => {
        if (!navigator.geolocation) return reject(new Error(t('app.loc_failed')));
        navigator.geolocation.getCurrentPosition(
          (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
          () => reject(new Error(t('app.loc_failed'))),
          { enableHighAccuracy: true, timeout: 10000 });
      };
      const lm = tg?.LocationManager;
      if (!lm || !tg.isVersionAtLeast?.('8.0')) return fallback();
      const ask = () => {
        if (!lm.isLocationAvailable) return fallback();
        lm.getLocation((loc) => {
          if (loc) return resolve(loc);
          if (lm.isAccessRequested && !lm.isAccessGranted) return reject(new Error(t('app.loc_denied')));
          fallback();
        });
      };
      lm.isInited ? ask() : lm.init(ask);
    });
  }

  async function locateMe(silent = false) {
    try {
      const loc = await getLocation();
      map.flyTo([loc.latitude, loc.longitude], 17, { duration: 0.8 });
      haptic();
    } catch (e) {
      if (!silent) toast(e.message);
      else reverseGeocode(mapCenter);
    }
  }

  function confirmMap() {
    if (!mapCenter) return;
    if (mapTarget === 'addr' && state.editAddr) {
      state.editAddr.location = { ...mapCenter, label: mapCenter.label || '' };
      if (!$('#addr-street').value.trim() && mapCenter.label) $('#addr-street').value = mapCenter.label;
      notify('success');
      back();
      return;
    }
    state.checkout.location = { ...mapCenter, label: mapCenter.label || '' };
    state.checkout.savedId = null;
    if (!$('#address').value.trim() && mapCenter.label) $('#address').value = mapCenter.label;
    notify('success');
    back();
  }

  // ------------------------------------------------------------ buyurtmalarim
  // Ikki bo'lim: to'lov kutilayotganlar va qolgan hammasi («Buyurtmalarim»)
  const ORDER_FILTERS = ['PENDING', 'HISTORY'];
  const inFilter = (o, f) => (f === 'PENDING' ? o.group === 'PENDING' : o.group !== 'PENDING');

  async function renderOrders() {
    const list = $('#orders-list');
    const bar = $('#orders-filter');
    bar.innerHTML = '';
    list.innerHTML = '<div class="skeleton" style="height:120px;margin:8px 0 12px"></div><div class="skeleton" style="height:120px"></div>';
    try {
      state.myOrders = await api('/api/orders/my?lang=' + state.lang);
      if (!state.myOrders.length) {
        list.innerHTML = `<div class="empty"><div class="big-emoji">🧾</div><b>${t('app.no_orders')}</b>${t('app.no_orders_hint')}</div>`;
        return;
      }
      if (!ORDER_FILTERS.includes(state.orderFilter)) {
        state.orderFilter = state.myOrders.some((o) => o.group === 'PENDING') ? 'PENDING' : 'HISTORY';
      }
      drawOrders();
    } catch (e) {
      list.innerHTML = `<div class="empty"><div class="big-emoji">😔</div><b>${t('app.load_failed')}</b>${esc(e.message)}</div>`;
    }
  }

  /** Manzil/raqamni o'zgartirish — faqat biz bilan bog'lanib (telefon bosilsa qo'ng'iroq). */
  function changeHint() {
    const ph = state.config?.phone;
    const digits = (ph || '').replace(/[^\d+]/g, '');
    return `<p class="oc-change">✏️ ${t('app.change_hint')}${ph
      ? ` <a href="tel:${esc(digits)}">☎️ ${esc(ph)}</a> <button type="button" class="copy-btn sm" data-copy="${esc(digits)}">📋</button>` : ''}</p>`;
  }

  /** «✅ Qabul qildim» — tasdiqlash so'raladi, keyin buyurtma «Topshirildi» bo'ladi. */
  function confirmReceived(id) {
    const go = async () => {
      try {
        await api(`/api/orders/${id}/received`, { method: 'POST' });
        notify('success');
        toast(t('app.received_thanks'));
        renderOrders();
      } catch (e) { toast(e.message); }
    };
    if (tg?.showConfirm) tg.showConfirm(t('app.received_confirm'), (ok) => { if (ok) go(); });
    else go();
  }

  function drawOrders() {
    const all = state.myOrders || [];
    const count = (f) => all.filter((o) => inFilter(o, f)).length;
    $('#orders-filter').innerHTML = ORDER_FILTERS.map((f) =>
      `<button class="of-chip${state.orderFilter === f ? ' on' : ''}" data-ofilter="${f}"><span>${esc(t('app.of.' + f))}</span><i>${count(f)}</i></button>`).join('');
    const shown = all.filter((o) => inFilter(o, state.orderFilter));
    const list = $('#orders-list');
    if (!shown.length) {
      list.innerHTML = `<div class="empty"><div class="big-emoji">🧾</div><b>${t('app.of.none')}</b></div>`;
      return;
    }
    list.innerHTML = shown.map((o) => {
      const at = new Date(o.createdAt);
      const hm = String(at.getHours()).padStart(2, '0') + ':' + String(at.getMinutes()).padStart(2, '0');
      return `
        <div class="oc-card">
          <div class="oc-head"><b>${t('app.order_n', o.id)}</b><span class="oc-status ${o.status}">${esc(o.statusLabel)}</span></div>
          <div class="oc-meta">📅 ${esc(fmtDate(at))} · 🕐 ${hm} · ${esc(o.deliveryLabel)}</div>
          <ul class="oc-items">${o.items.map((i) => `<li><span>${esc(i.productName)}${i.variantName ? ' · ' + esc(i.variantName) : ''} <i>× ${i.quantity}</i></span><b>${money(i.total)}</b></li>`).join('')}</ul>
          <dl class="oc-info">
            <dt>${t('app.pay_method')}</dt><dd>${esc(o.methodLabel)}</dd>
            <dt>${t('app.pay_status')}</dt><dd>${esc(o.paymentLabel)}</dd>
          </dl>
          <div class="cs-line"></div>
          <div class="oc-total"><span>${t('app.total')}</span><span>${money(o.total)}</span></div>
          ${!['DELIVERED', 'CANCELLED'].includes(o.status) ? changeHint() : ''}
          ${o.trackingUrl ? `<button class="cs-btn track-btn" data-track="${esc(o.trackingUrl)}">📍 ${t('app.track')}</button>` : ''}
          ${o.canConfirmReceived ? `<button class="cs-btn received-btn" data-received="${o.id}">${t('app.received')}</button>` : ''}
          ${o.canUploadReceipt ? `<button class="cs-btn upload-btn" data-upload="${o.id}">${t('app.upload_receipt')}</button>` : ''}
          ${o.cancelUntil && o.cancelUntil > Date.now() ? cancelBlock(o.id, o.cancelUntil, false) : ''}
        </div>`;
    }).join('');
  }

  // ------------------------------------------------------------ pastki panel
  function renderBottom() {
    const bar = $('#bottom-bar');
    const btn = $('#bottom-btn');
    const count = cartCount();
    let html = null;
    // Pastki tablar: Asosiy / Savat / Profil (ichki sahifalarda yashirinadi)
    $('#tabbar').hidden = !TABBAR_VIEWS.has(state.view);
    $('#tab-badge').hidden = !count;
    $('#tab-badge').textContent = count;
    bar.hidden = !html;
    if (html) btn.innerHTML = html;
    btn.disabled = state.submitting;
  }

  function onBottom() {
    haptic();
    if (state.view === 'menu') go('cart');
    else if (state.view === 'cart') go('checkout');
    else if (state.view === 'checkout') submitOrder();
  }

  // ------------------------------------------------------------ hodisalar
  function bind() {
    $('#orders-list').addEventListener('click', (e) => {
      const cp = e.target.closest('[data-copy]');
      if (cp) { copyText(cp.dataset.copy, t('app.phone_copied')); return; }
      const r = e.target.closest('[data-received]');
      if (r) { confirmReceived(Number(r.dataset.received)); return; }
      const b = e.target.closest('[data-track]');
      if (!b) return;
      haptic();
      if (tg?.openLink) tg.openLink(b.dataset.track); else window.open(b.dataset.track, '_blank');
    });
    $('#orders-filter').addEventListener('click', (e) => {
      const b = e.target.closest('[data-ofilter]');
      if (!b || b.dataset.ofilter === state.orderFilter) return;
      haptic();
      state.orderFilter = b.dataset.ofilter;
      drawOrders();
    });
    $('#catalog').addEventListener('click', (e) => {
      const like = e.target.closest('[data-like]');
      if (like) { e.stopPropagation(); toggleLike(findProduct(Number(like.dataset.like))); return; }
      const add = e.target.closest('[data-add]');
      if (add) { e.stopPropagation(); quickAdd(findProduct(Number(add.dataset.add))); return; }
      const card = e.target.closest('[data-product]');
      if (card) openSheet(findProduct(Number(card.dataset.product)));
    });
    $('#btn-browse').addEventListener('click', () => { haptic(); state.history = []; go('catalog', { push: false }); });
    $('#cat-tiles').addEventListener('click', (e) => {
      const tile = e.target.closest('[data-open]');
      if (tile) openCategory(tile.dataset.open);
    });
    $('#tabbar').addEventListener('click', (e) => {
      const tab = e.target.closest('[data-tab]');
      if (tab) switchTab(tab.dataset.tab);
    });
    $('#btn-contact').addEventListener('click', openContact);
    $('#btn-about').addEventListener('click', openAbout);
    $('#btn-terms').addEventListener('click', openTerms);
    $('#terms-backdrop').addEventListener('click', closeTerms);
    $('#btn-my-orders').addEventListener('click', () => { haptic(); go('orders'); });
    $('#btn-my-likes').addEventListener('click', () => openCategory('mine'));  // profil: Yoqtirganlarim kartasi
    $('#about-backdrop').addEventListener('click', closeAbout);
    $('#btn-instagram').addEventListener('click', () => {
      const ig = state.config?.instagram;
      if (ig) openExternal('https://www.instagram.com/' + ig + '/');
    });
    // Til va Aloqa oynalaridagi «‹ Orqaga» tugmasi
    $$('[data-sheet-back]').forEach((b) => b.addEventListener('click', () => { haptic(); back(); }));
    $('#contact-backdrop').addEventListener('click', closeContact);
    $('#contact-list').addEventListener('click', (e) => {
      const cp = e.target.closest('[data-copy]');
      if (cp) { e.preventDefault(); e.stopPropagation(); copyText(cp.dataset.copy, t('app.phone_copied')); return; }
      const a = e.target.closest('[data-link]');
      if (!a) return;
      e.preventDefault();
      openExternal(a.dataset.link);
    });

    $('#sheet-body').addEventListener('click', (e) => {
      if (!sheetState) return;
      const like = e.target.closest('[data-like]');
      if (like) { toggleLike(sheetState.product).then(() => sheetState && renderSheet()); renderSheet(); return; }
      const star = e.target.closest('[data-rate]');
      if (star) { rateProduct(sheetState.product, Number(star.dataset.rate)); return; }
      const variant = e.target.closest('[data-variant]');
      const step = e.target.closest('[data-sq]');
      if (variant) { sheetState.variantId = Number(variant.dataset.variant); haptic(); renderSheet(); }
      if (step) { sheetState.qty = Math.max(1, Math.min(50, sheetState.qty + Number(step.dataset.sq))); haptic(); renderSheet(); }
      if (e.target.closest('#sheet-add')) {
        const { product, variantId, qty } = sheetState;
        setQty(variantId, cartQty(variantId) + qty);
        notify('success');
        toast(t('app.added', product.name));
        closeSheet();
        refreshCard(product.id);
      }
    });
    $('#sheet-backdrop').addEventListener('click', closeSheet);
    $('#sheet-close').addEventListener('click', closeSheet);

    $('#btn-lang').addEventListener('click', openLangSheet);
    $('#lang-backdrop').addEventListener('click', closeLangSheet);
    $('#lang-options').addEventListener('click', (e) => {
      const row = e.target.closest('[data-lang]');
      if (row) setLang(row.dataset.lang);
    });

    $('#cart-back').addEventListener('click', () => { haptic(); state.history.length ? back() : switchTab('menu'); });
    $('#cart-summary').addEventListener('click', (e) => {
      if (e.target.closest('#cart-checkout')) { haptic(); go('checkout'); }
    });
    $('#cart-list').addEventListener('click', (e) => {
      if (e.target.closest('[data-browse]')) { haptic(); state.history = []; go('catalog'); return; }
      const like = e.target.closest('[data-like]');
      if (like) { toggleLike(findProduct(Number(like.dataset.like))); return; }
      const rm = e.target.closest('[data-rm]');
      if (rm) {
        const id = Number(rm.dataset.rm);
        const productId = state.variants.get(id).product.id;
        setQty(id, 0);
        haptic('medium');
        renderCart();
        refreshCard(productId);
        return;
      }
      const b = e.target.closest('[data-cq]');
      if (!b) return;
      const id = Number(b.dataset.v);
      const productId = state.variants.get(id).product.id;
      setQty(id, cartQty(id) + Number(b.dataset.cq));
      haptic();
      renderCart();
      refreshCard(productId);
    });
    $('#btn-clear').addEventListener('click', () => {
      state.cart = [];
      saveCart();
      renderCart();
      renderBottom();
      renderMenu();
    });

    $('#checkout-summary').addEventListener('click', (e) => {
      if (e.target.closest('#checkout-submit')) { haptic(); submitOrder(); }
    });
    $('#btn-map').addEventListener('click', () => { haptic(); mapTarget = 'checkout'; go('map'); });
    $('#btn-my-addresses').addEventListener('click', () => { haptic(); go('addresses'); });
    $('#addresses-list').addEventListener('click', (e) => {
      const add = e.target.closest('[data-addr-new]');
      const card = e.target.closest('[data-addr]');
      if (add) { haptic(); state.editAddr = { id: null, label: '', location: null, address: '' }; go('address-edit'); return; }
      if (card) {
        const a = (state.addresses || []).find((x) => x.id === Number(card.dataset.addr));
        if (!a) return;
        haptic();
        state.editAddr = { id: a.id, label: a.label, address: a.address || '', location: { lat: a.latitude, lng: a.longitude, label: a.mapLabel || '' } };
        go('address-edit');
      }
    });
    $('#addr-presets').addEventListener('click', (e) => {
      const b = e.target.closest('[data-preset]');
      if (!b) return;
      haptic();
      $('#addr-name').value = b.dataset.preset;
      $$('#addr-presets .addr-preset').forEach((x) => x.classList.toggle('on', x === b));
    });
    $('#addr-map').addEventListener('click', () => {
      haptic();
      state.editAddr.label = $('#addr-name').value;
      state.editAddr.address = $('#addr-street').value;
      mapTarget = 'addr';
      go('map');
    });
    $('#addr-save').addEventListener('click', saveAddress);
    $('#addr-delete').addEventListener('click', deleteAddress);
    $('#saved-addrs').addEventListener('click', (e) => {
      const b = e.target.closest('[data-use-addr]');
      if (!b) return;
      const a = (state.addresses || []).find((x) => x.id === Number(b.dataset.useAddr));
      if (!a) return;
      haptic();
      state.checkout.location = { lat: a.latitude, lng: a.longitude, label: a.mapLabel || '' };
      state.checkout.savedId = a.id;
      $('#address').value = a.address || a.mapLabel || '';
      renderCheckout();
    });
    $('#btn-locate').addEventListener('click', () => locateMe(false));
    $('#btn-map-ok').addEventListener('click', confirmMap);
    $('#phone').addEventListener('input', (e) => { e.target.value = formatPhone(e.target.value); });
    $('#checkout-form').addEventListener('submit', (e) => { e.preventDefault(); submitOrder(); });

    $('#bottom-btn').addEventListener('click', onBottom);
    $$('[data-back]').forEach((b) => b.addEventListener('click', back));
    $('#btn-close').addEventListener('click', () => (inTelegram ? tg.close() : go(state.home, { push: false })));
    $('#btn-more').addEventListener('click', () => { state.history = []; go(state.home, { push: false }); });

    // «Chek yuklash» tugmalari (muvaffaqiyat sahifasi va «Buyurtmalarim»)
    document.addEventListener('click', (e) => {
      const b = e.target.closest('[data-upload]');
      if (!b || b.disabled) return;
      uploadTarget = Number(b.dataset.upload);
      haptic();
      $('#receipt-input').value = '';
      $('#receipt-input').click();
    });
    $('#receipt-input').addEventListener('change', (e) => {
      const file = e.target.files?.[0];
      if (file && uploadTarget) uploadReceipt(uploadTarget, file);
    });

    window.addEventListener('resize', () => { if (state.view === 'menu') fitTaglines(); });
    if (tg) tg.BackButton.onClick(back);
  }

  // ------------------------------------------------------------ ishga tushirish
  async function init() {
    if (tg) {
      tg.ready();
      tg.expand();
      try { tg.disableVerticalSwipes?.(); } catch (_) { /* eski versiya */ }
      if (inTelegram) document.body.classList.add('tg');
    }
    paintChrome();
    bind();
    // Avval kirgan bo'lsa — darhol mahsulotlar; birinchi marta bo'lsa — kirish sahifasi (va eslab qolamiz)
    checkWelcomeSeen((seen) => (seen ? skipWelcome() : markWelcomeSeen()));

    // Telegram ichida asosiy manba — server (botda ham, Mini App'da ham tanlangan oxirgi til).
    // Server javob bermasa: URL (?lang=) → shu qurilmada tanlangan → Telegram tili.
    state.lang = initialLang().lang;
    if (inTelegram) {
      try {
        const me = await api('/api/me');
        if (LANGS.includes(me.language)) state.lang = me.language;
        state.mePhone = me.phone; // botda ulashilgan raqam
        state.meName = me.name;   // botda yozilgan ism
        api('/api/me/likes').then((ids) => { state.liked = new Set(ids); renderMenu(); }).catch(() => {});
        api('/api/me/ratings').then((r) => { state.myRatings = r.ratings || {}; state.purchased = new Set(r.purchased || []); }).catch(() => {});
      } catch (_) { /* taxminiy til qoladi */ }
    }
    storage('hb_lang', state.lang);

    try {
      await loadContent();
      applyStatic();
      cleanCart();
      renderMenu();
      renderBottom();
    } catch (e) {
      $('#catalog').innerHTML = `<div class="empty"><div class="big-emoji">😔</div><b>${state.dict['app.menu_failed'] || 'Menyuni yuklab bo\'lmadi'}</b>${esc(e.message)}<br><br><button class="btn tinted" onclick="location.reload()">↻</button></div>`;
    }
  }

  init();
})();
