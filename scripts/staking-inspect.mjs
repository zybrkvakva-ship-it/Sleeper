#!/usr/bin/env node
/**
 * Инспекция стейк-аккаунтов Guardian (SKR) для выявления точного layout.
 * Вызов: node scripts/staking-inspect.mjs [RPC_URL] [WALLET_BASE58]
 * Пример: node scripts/staking-inspect.mjs
 *         node scripts/staking-inspect.mjs '' 7xKXtYRq8sS2xEo6t3L9vYqP2mN4bR5cD6eF7gH8iJ9k
 *
 * Без WALLET: getProgramAccounts без memcmp, первые 10 аккаунтов, дамп первых 80 байт.
 * С WALLET: getProgramAccounts с memcmp offset=0 и offset=8, дамп данных.
 */

const SKR_STAKING_PROGRAM_ID = 'SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ';
const SKR_MINT = 'SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3';
const TOKEN_PROGRAM_ID = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA';
const SKR_DECIMALS = 6;
// SPL Token account: mint 32, owner 32, amount 8 @ 64
const TOKEN_ACCOUNT_DATA_SIZE = 165;
const TOKEN_ACCOUNT_OWNER_OFFSET = 32;
const TOKEN_ACCOUNT_AMOUNT_OFFSET = 64;
const defaultRpc = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';

// argv[2]: RPC URL или только Helius API key (тогда подставляем https://mainnet.helius-rpc.com/?api-key=KEY)
const arg1 = process.argv[2] || '';
const rpcUrl = !arg1
  ? defaultRpc
  : /^https?:\/\//i.test(arg1)
    ? arg1
    : `https://mainnet.helius-rpc.com/?api-key=${arg1}`;
const walletFilter = process.argv[3] || null; // optional: base58 wallet to filter

function bufToHex(buf, max = 80) {
  const len = Math.min(buf.length, max);
  let s = '';
  for (let i = 0; i < len; i++) s += buf[i].toString(16).padStart(2, '0');
  if (buf.length > max) s += '...';
  return s;
}

function readU64LE(buf, offset) {
  if (offset + 8 > buf.length) return null;
  const lo =
    (buf[offset] & 0xff) |
    ((buf[offset + 1] & 0xff) << 8) |
    ((buf[offset + 2] & 0xff) << 16) |
    ((buf[offset + 3] & 0xff) << 24);
  const hi =
    (buf[offset + 4] & 0xff) |
    ((buf[offset + 5] & 0xff) << 8) |
    ((buf[offset + 6] & 0xff) << 16) |
    ((buf[offset + 7] & 0xff) << 24);
  return lo + hi * 0x100000000;
}

async function rpc(method, params) {
  const res = await fetch(rpcUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  const json = await res.json();
  if (json.error) throw new Error(`RPC error: ${JSON.stringify(json.error)}`);
  return json.result;
}

function parseAccountData(dataB64) {
  const raw = Buffer.from(dataB64, 'base64');
  const data = new Uint8Array(raw);
  const size = data.length;

  const hex80 = bufToHex(data, 80);

  // Вариант A: без дискриминатора — owner @ 0, amount @ 32, unlock_ts @ 40
  const amountAt32 = size >= 40 ? readU64LE(data, 32) : null;
  const unlockAt40 = size >= 48 ? readU64LE(data, 40) : null;

  // Вариант B: 8-byte discriminator — owner @ 8, amount @ 40, unlock_ts @ 48
  const disc8 = size >= 8 ? bufToHex(data, 8) : '';
  const amountAt40 = size >= 48 ? readU64LE(data, 40) : null;
  const unlockAt48 = size >= 56 ? readU64LE(data, 48) : null;

  return {
    size,
    hex80,
    disc8,
    amountAt32,
    amountAt40,
    unlockAt40: unlockAt40 != null ? (unlockAt40 > 0 && unlockAt40 < 2e12 ? new Date(unlockAt40 * 1000).toISOString() : unlockAt40) : null,
    unlockAt48: unlockAt48 != null ? (unlockAt48 > 0 && unlockAt48 < 2e12 ? new Date(unlockAt48 * 1000).toISOString() : unlockAt48) : null,
    humanAt32: amountAt32 != null ? amountAt32 / Math.pow(10, SKR_DECIMALS) : null,
    humanAt40: amountAt40 != null ? amountAt40 / Math.pow(10, SKR_DECIMALS) : null,
  };
}

async function main() {
  console.log('=== SKR Guardian staking layout inspector ===\n');
  console.log('RPC:', rpcUrl.replace(/api-key=[^&]+/, 'api-key=***'));
  console.log('Program:', SKR_STAKING_PROGRAM_ID);
  if (walletFilter) console.log('Wallet filter (base58):', walletFilter.slice(0, 8) + '...' + walletFilter.slice(-6));
  console.log('');

  try {
    if (!walletFilter) {
      // Один запрос: без dataSlice чтобы получить полный размер данных у первых аккаунтов
      const paramsNoSlice = [
        SKR_STAKING_PROGRAM_ID,
        { encoding: 'base64', commitment: 'confirmed' },
      ];
      console.log('Calling getProgramAccounts (no filters, full data) — first 5 accounts data length...');
      const allAccounts = await rpc('getProgramAccounts', paramsNoSlice);
      console.log('Total accounts:', allAccounts?.length ?? 0);
      const toInspect = (allAccounts || []).slice(0, 5);
      for (let i = 0; i < toInspect.length; i++) {
        const acc = toInspect[i];
        const dataRaw = acc.account?.data;
        const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) ? Buffer.from(dataRaw).toString('base64') : null);
        if (!dataB64) continue;
        const buf = Buffer.from(dataB64, 'base64');
        console.log('  Account ' + i + ' pubkey=' + acc.pubkey + ' data.length=' + buf.length + ' hex(0..min(80,len))=' + bufToHex(buf, 80));
      }
      // Найти первый аккаунт с данными >= 40 байт для разбора layout
      const bigAccounts = (allAccounts || []).filter((acc) => {
        const d = acc.account?.data;
        const b64 = typeof d === 'string' ? d : (Array.isArray(d) ? Buffer.from(d).toString('base64') : null);
        return b64 && Buffer.from(b64, 'base64').length >= 40;
      });
      console.log('\nAccounts with data.length >= 40:', bigAccounts.length);
      const accounts = bigAccounts.length ? bigAccounts : (allAccounts || []).slice(0, 3);
      const toShow = Math.min(10, accounts.length);
      console.log('Showing first', toShow, 'account(s):\n');
      for (let i = 0; i < toShow; i++) {
        const acc = accounts[i];
        const pubkey = acc.pubkey;
        const dataRaw = acc.account?.data;
        const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) ? Buffer.from(dataRaw).toString('base64') : null);
        if (!dataB64) {
          console.log(`[${i}] ${pubkey} — no data`);
          continue;
        }
        const p = parseAccountData(dataB64);
        console.log(`--- Account ${i} ---`);
        console.log('pubkey:', pubkey);
        console.log('data size (from slice):', p.size, 'bytes');
        console.log('hex [0..80]:', p.hex80);
        console.log('first 8 bytes (discriminator?):', p.disc8);
        console.log('Layout A (owner@0 amount@32): amount(raw)=', p.amountAt32, 'human=', p.humanAt32, 'unlock_ts@40=', p.unlockAt40);
        console.log('Layout B (disc@0 owner@8 amount@40): amount(raw)=', p.amountAt40, 'human=', p.humanAt40, 'unlock_ts@48=', p.unlockAt48);
        console.log('');
      }
      return;
    }

    // Load PublicKey once for base64 memcmp and PDA
    const { createRequire } = await import('module');
    const require = createRequire(import.meta.url);
    const path = (await import('path')).default;
    const { fileURLToPath } = await import('url');
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    let PublicKey;
    try {
      PublicKey = require(path.join(__dirname, '../backend/node_modules/@solana/web3.js')).PublicKey;
    } catch (e) {
      PublicKey = null;
    }
    const walletBytes32 = PublicKey ? new PublicKey(walletFilter).toBuffer() : null;
    const guardianBytes32 = PublicKey ? new PublicKey(SKR_STAKING_PROGRAM_ID).toBuffer() : null;
    const walletBase64 = walletBytes32 && walletBytes32.length === 32 ? Buffer.from(walletBytes32).toString('base64') : null;

    // 1) Токен-аккаунты SKR у кошелька (owner) — свободный баланс и возможно стейк-контракты
    console.log('--- getTokenAccountsByOwner (wallet, mint=SKR) ---');
    const tokenAccounts = await rpc('getTokenAccountsByOwner', [
      walletFilter,
      { mint: SKR_MINT },
      { encoding: 'jsonParsed', commitment: 'confirmed' },
    ]);
    const value = tokenAccounts?.value ?? [];
    console.log('Token accounts (SKR) count:', value.length);
    let totalOwnerSkr = 0;
    for (let i = 0; i < value.length; i++) {
      const v = value[i];
      const info = v.account?.data?.parsed?.info;
      if (info) {
        const amount = info.tokenAmount?.amount ?? info.amount ?? '?';
        const decimals = info.tokenAmount?.decimals ?? SKR_DECIMALS;
        const uiAmount = info.tokenAmount?.uiAmount ?? (Number(amount) / Math.pow(10, decimals));
        totalOwnerSkr += Number(uiAmount);
        console.log('  [' + i + '] pubkey=' + v.pubkey + ' amount(raw)=' + amount + ' human=' + uiAmount);
      } else {
        console.log('  [' + i + '] pubkey=' + v.pubkey + ' (parse manually)');
      }
    }
    if (value.length > 0) console.log('  Total SKR (owner):', totalOwnerSkr);

    // 1a) ПРАВИЛЬНЫЙ МЕТОД: getTokenAccountsByDelegate(GuardianProgramId, mint=SKR) — delegate = программа Guardian
    // Возвращает все SKR токен-аккаунты, где делегат = Guardian; фильтруем по owner == наш кошелёк, суммируем delegatedAmount
    console.log('\n--- getTokenAccountsByDelegate (GUARDIAN program as delegate, mint=SKR) — CORRECT staking method ---');
    let stakedByGuardianDelegate = [];
    let totalStakedForWallet = 0;
    let totalStakedRaw = 0;
    try {
      const byGuardianDelegate = await rpc('getTokenAccountsByDelegate', [
        SKR_STAKING_PROGRAM_ID,
        { mint: SKR_MINT },
        { encoding: 'jsonParsed', commitment: 'confirmed' },
      ]);
      const listGuardian = byGuardianDelegate?.value ?? [];
      console.log('Token accounts where delegate=Guardian and mint=SKR (total):', listGuardian.length);
      for (let i = 0; i < listGuardian.length; i++) {
        const v = listGuardian[i];
        const info = v.account?.data?.parsed?.info;
        if (!info) continue;
        const owner = info.owner || info.ownerAddress || '';
        const tokenAmount = info.tokenAmount || {};
        const delegatedAmount = info.delegatedAmount || {};
        const amountRaw = tokenAmount.amount != null ? String(tokenAmount.amount) : '0';
        const delegatedRaw = delegatedAmount.amount != null ? String(delegatedAmount.amount) : amountRaw;
        const decimals = tokenAmount.decimals ?? SKR_DECIMALS;
        const humanDelegated = Number(delegatedRaw) / Math.pow(10, decimals);
        if (owner === walletFilter) {
          stakedByGuardianDelegate.push({ pubkey: v.pubkey, owner, delegatedRaw, humanDelegated });
          totalStakedRaw += Number(delegatedRaw);
          totalStakedForWallet += humanDelegated;
          console.log('  [OUR WALLET] pubkey=' + v.pubkey + ' owner=' + owner.slice(0, 8) + '... delegatedAmount(raw)=' + delegatedRaw + ' human=' + humanDelegated);
        }
      }
      if (totalStakedForWallet > 0) {
        console.log('  >>> STAKED SKR FOR THIS WALLET: raw=' + totalStakedRaw + ' human=' + totalStakedForWallet);
      } else if (listGuardian.length > 0) {
        console.log('  (No account owned by our wallet in this list; sample owners: ' + listGuardian.slice(0, 3).map((x) => (x.account?.data?.parsed?.info?.owner || '').slice(0, 8)).join(', ') + '...)');
      }
    } catch (e) {
      console.log('  Error:', e.message);
      if (e.message && e.message.includes('excluded from account secondary indexes')) {
        console.log('  (RPC may not index delegate by program ID; will try getProgramAccounts Token + memcmp delegate)');
      }
    }

    // 1b) Токен-аккаунты SKR, где кошелёк — delegate (старый вариант: delegate=wallet)
    console.log('\n--- getTokenAccountsByDelegate (wallet as delegate, filter=mint=SKR) ---');
    let delegateSkrList = [];
    try {
      const byDelegateSkr = await rpc('getTokenAccountsByDelegate', [
        walletFilter,
        { mint: SKR_MINT },
        { encoding: 'jsonParsed', commitment: 'confirmed' },
      ]);
      delegateSkrList = byDelegateSkr?.value ?? [];
    } catch (e) {
      if (e.message && e.message.includes('excluded from account secondary indexes')) {
        console.log('  (RPC не поддерживает этот метод для данного ключа; попробуйте другой RPC или фильтр)');
      } else throw e;
    }
    console.log('Token accounts (delegate=wallet, mint=SKR) count:', delegateSkrList.length);
    let totalDelegateSkr = 0;
    for (let i = 0; i < delegateSkrList.length; i++) {
      const v = delegateSkrList[i];
      const info = v.account?.data?.parsed?.info;
      if (info?.tokenAmount) {
        const ui = info.tokenAmount.uiAmount ?? Number(info.tokenAmount.amount || 0) / Math.pow(10, info.tokenAmount.decimals ?? 6);
        const delegated = info.delegatedAmount?.uiAmount ?? (info.delegatedAmount?.amount != null ? Number(info.delegatedAmount.amount) / Math.pow(10, info.tokenAmount.decimals ?? 6) : null);
        totalDelegateSkr += Number(ui ?? 0);
        console.log('  [' + i + '] pubkey=' + v.pubkey + ' amount=' + ui + ' delegatedAmount=' + delegated);
      }
    }
    if (delegateSkrList.length > 0) console.log('  Total SKR (delegate):', totalDelegateSkr);

    // 2) getProgramAccounts memcmp offset 41 (real Guardian layout), then 0, 8, 24, 32, 40 (RPC bytes=base58)
    for (const offset of [41, 0, 8, 24, 32, 40]) {
      const params = [
        SKR_STAKING_PROGRAM_ID,
        {
          encoding: 'base64',
          commitment: 'confirmed',
          filters: [{ memcmp: { offset, bytes: walletFilter } }],
        },
      ];
      console.log('\n--- getProgramAccounts memcmp offset=' + offset + ' bytes=wallet(base58) ---');
      const accounts = await rpc('getProgramAccounts', params);
      console.log('Accounts found:', accounts?.length ?? 0);
      if (!accounts || accounts.length === 0) continue;
      for (let i = 0; i < accounts.length; i++) {
        const acc = accounts[i];
        const dataRaw = acc.account?.data;
        const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) && dataRaw.length > 0 ? dataRaw[0] : null);
        if (!dataB64) continue;
        const p = parseAccountData(dataB64);
        const buf = Buffer.from(dataB64, 'base64');
        const amountOffsets = [73, 81, 89, 97, 105, 120];
        const amounts = buf.length >= 105 + 8 ? amountOffsets.map((ao) => (buf.length >= ao + 8 ? readU64LE(buf, ao) : null)) : [];
        console.log('Account ' + i + ': pubkey=' + acc.pubkey + ' dataLen=' + p.size);
        if (amounts.length > 0) console.log('  amount@73,81,89,97,105,120 (raw):', amounts.map((a) => (a != null ? a : '-')).join(', '), 'human@105:', amounts[4] != null ? amounts[4] / 1e6 : '-');
        console.log('  hex[0..80]:', p.hex80);
        console.log('  amount@32:', p.amountAt32, 'human:', p.humanAt32);
        console.log('  amount@40:', p.amountAt40, 'human:', p.humanAt40);
        console.log('  unlock@40:', p.unlockAt40, 'unlock@48:', p.unlockAt48);
      }
    }

    // 2a-finalized) То же memcmp, но commitment "finalized" (рекомендация из разбора)
    console.log('\n--- getProgramAccounts memcmp commitment=finalized ---');
    for (const offset of [0, 8]) {
      try {
        const params = [
          SKR_STAKING_PROGRAM_ID,
          { encoding: 'base64', commitment: 'finalized', filters: [{ memcmp: { offset, bytes: walletFilter } }] },
        ];
        const accounts = await rpc('getProgramAccounts', params);
        console.log('  offset=' + offset + ' finalized: accounts=' + (accounts?.length ?? 0));
        if (accounts && accounts.length > 0) {
          for (let i = 0; i < Math.min(3, accounts.length); i++) {
            const acc = accounts[i];
            const dataRaw = acc.account?.data;
            const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) && dataRaw[0] ? String(dataRaw[0]) : null);
            if (dataB64) {
              const p = parseAccountData(dataB64);
              console.log('    pubkey=' + acc.pubkey + ' amount@32=' + p.amountAt32 + ' amount@40=' + p.amountAt40);
            }
          }
        }
      } catch (e) {
        console.log('  offset=' + offset + ' error:', e.message);
      }
    }

    // 2a-datasize) memcmp + dataSize (рекомендация: сузить по размеру аккаунта)
    console.log('\n--- getProgramAccounts memcmp + dataSize (40, 48, 56, 72) ---');
    for (const offset of [0, 8]) {
      for (const dataSize of [40, 48, 56, 72]) {
        try {
          const params = [
            SKR_STAKING_PROGRAM_ID,
            {
              encoding: 'base64',
              commitment: 'confirmed',
              filters: [
                { memcmp: { offset, bytes: walletFilter } },
                { dataSize },
              ],
            },
          ];
          const accounts = await rpc('getProgramAccounts', params);
          const count = accounts?.length ?? 0;
          if (count > 0) console.log('  offset=' + offset + ' dataSize=' + dataSize + ': ' + count + ' accounts');
          if (count > 0 && count <= 5) {
            for (const acc of accounts) {
              const dataRaw = acc.account?.data;
              const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) && dataRaw[0] ? String(dataRaw[0]) : null);
              if (dataB64) {
                const p = parseAccountData(dataB64);
                console.log('    [WALLET] ' + acc.pubkey + ' amount@32=' + p.amountAt32 + ' amount@40=' + p.amountAt40 + ' human=' + (p.humanAt32 ?? p.humanAt40));
              }
            }
          }
        } catch (e) {
          console.log('  offset=' + offset + ' dataSize=' + dataSize + ': ' + e.message);
        }
      }
    }

    // 2-discovery) Discovery: getProgramAccounts с dataSlice, поиск 32 байт кошелька в данных
    console.log('\n--- Discovery: getProgramAccounts dataSlice, search wallet bytes in data ---');
    if (walletBytes32) {
      try {
        const paramsSlice = [
          SKR_STAKING_PROGRAM_ID,
          { encoding: 'base64', commitment: 'confirmed', dataSlice: { offset: 0, length: 200 } },
        ];
        const sliceAccs = await rpc('getProgramAccounts', paramsSlice);
        const list = sliceAccs || [];
        console.log('Accounts returned with dataSlice(0,200):', list.length);
        const foundOffsets = new Set();
        let scanned = 0;
        for (const acc of list) {
          const dataRaw = acc.account?.data;
          const buf = typeof dataRaw === 'string' ? Buffer.from(dataRaw, 'base64') : (Array.isArray(dataRaw) && dataRaw[0] ? Buffer.from(String(dataRaw[0]), 'base64') : null);
          if (!buf || buf.length < 32) continue;
          scanned++;
          for (let pos = 0; pos <= buf.length - 32; pos++) {
            if (buf.slice(pos, pos + 32).equals(walletBytes32)) {
              foundOffsets.add(pos);
              console.log('  [FOUND] pubkey=' + acc.pubkey + ' wallet bytes at offset=' + pos + ' dataLen=' + buf.length);
              const amount32 = readU64LE(buf, 32);
              const amount40 = buf.length >= 48 ? readU64LE(buf, 40) : null;
              console.log('    amount@32=' + amount32 + ' amount@40=' + amount40 + ' human@32=' + (amount32 != null ? amount32 / 1e6 : null) + ' human@40=' + (amount40 != null ? amount40 / 1e6 : null));
              break;
            }
          }
        }
        console.log('Scanned', scanned, 'accounts with data>=32, offsets where wallet found:', [...foundOffsets].sort((a, b) => a - b).join(', ') || 'none');
        for (const off of foundOffsets) {
          const paramsMemcmp = [
            SKR_STAKING_PROGRAM_ID,
            { encoding: 'base64', commitment: 'confirmed', filters: [{ memcmp: { offset: off, bytes: walletFilter } }] },
          ];
          const retry = await rpc('getProgramAccounts', paramsMemcmp);
          console.log('  Retry getProgramAccounts memcmp offset=' + off + ': accounts=' + (retry?.length ?? 0));
        }
      } catch (e) {
        console.log('Discovery error:', e.message);
      }
    }

    // 2b) getProgramAccounts memcmp bytes=base64(32-byte wallet) — часть RPC ожидает base64
    if (walletBase64) {
      for (const offset of [0, 8]) {
        const params = [
          SKR_STAKING_PROGRAM_ID,
          {
            encoding: 'base64',
            commitment: 'confirmed',
            filters: [{ memcmp: { offset, bytes: walletBase64 } }],
          },
        ];
        console.log('\n--- getProgramAccounts memcmp offset=' + offset + ' bytes=wallet(base64) ---');
        try {
          const accounts = await rpc('getProgramAccounts', params);
          console.log('Accounts found:', accounts?.length ?? 0);
          if (accounts && accounts.length > 0) {
            for (let i = 0; i < accounts.length; i++) {
              const acc = accounts[i];
              const dataRaw = acc.account?.data;
              const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) ? Buffer.from(dataRaw).toString('base64') : null);
              if (!dataB64) continue;
              const p = parseAccountData(dataB64);
              console.log('Account ' + i + ': pubkey=' + acc.pubkey + ' dataLen=' + p.size);
              console.log('  amount@32:', p.amountAt32, 'human:', p.humanAt32, 'amount@40:', p.amountAt40, 'human:', p.humanAt40);
            }
          }
        } catch (e) {
          console.log('RPC error:', e.message);
        }
      }
    }

    // 2c) getProgramAccounts БЕЗ фильтров — общее число аккаунтов программы и размеры данных
    console.log('\n--- getProgramAccounts (no filters) — total count and data sizes ---');
    try {
      const paramsNoFilter = [
        SKR_STAKING_PROGRAM_ID,
        { encoding: 'base64', commitment: 'confirmed' },
      ];
      const allAccs = await rpc('getProgramAccounts', paramsNoFilter);
      const total = allAccs?.length ?? 0;
      console.log('Total accounts (no filter):', total);
      if (total > 0) {
        const sizeCounts = {};
        let withWalletAt0 = 0;
        let withWalletAt8 = 0;
        const sample = (allAccs || []).slice(0, 50);
        for (const acc of sample) {
          const dataRaw = acc.account?.data;
          const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) ? Buffer.from(dataRaw).toString('base64') : null);
          if (!dataB64) continue;
          const buf = Buffer.from(dataB64, 'base64');
          const len = buf.length;
          sizeCounts[len] = (sizeCounts[len] || 0) + 1;
          if (walletBytes32 && len >= 32 && buf.slice(0, 32).equals(walletBytes32)) withWalletAt0++;
          if (walletBytes32 && len >= 40 && buf.slice(8, 40).equals(walletBytes32)) withWalletAt8++;
        }
        console.log('Sample (first 50) data size distribution:', JSON.stringify(sizeCounts));
        if (walletBytes32) console.log('In sample: owner@0 matches=' + withWalletAt0 + ' owner@8 matches=' + withWalletAt8);
        if (total <= 100 && walletBytes32) {
          for (const acc of allAccs || []) {
            const dataB64 = typeof acc.account?.data === 'string' ? acc.account.data : (Array.isArray(acc.account?.data) ? Buffer.from(acc.account.data).toString('base64') : null);
            if (!dataB64) continue;
            const buf = Buffer.from(dataB64, 'base64');
            const at0 = buf.length >= 32 && buf.slice(0, 32).equals(walletBytes32);
            const at8 = buf.length >= 40 && buf.slice(8, 40).equals(walletBytes32);
            if (at0 || at8) {
              const p = parseAccountData(dataB64);
              console.log('  [WALLET] pubkey=' + acc.pubkey + ' owner@' + (at0 ? '0' : '8') + ' amount@32=' + p.amountAt32 + ' amount@40=' + p.amountAt40 + ' human=' + (p.humanAt32 ?? p.humanAt40));
            }
          }
        }
      }
    } catch (e) {
      console.log('Error:', e.message);
    }

    // 2d) getProgramAccounts с dataSize — сколько аккаунтов каждого размера
    console.log('\n--- getProgramAccounts with dataSize filter (count by size) ---');
    for (const dataSize of [2, 8, 16, 40, 48, 56, 64, 72, 80]) {
      try {
        const params = [
          SKR_STAKING_PROGRAM_ID,
          {
            encoding: 'base64',
            commitment: 'confirmed',
            filters: [{ dataSize }],
          },
        ];
        const accounts = await rpc('getProgramAccounts', params);
        const count = accounts?.length ?? 0;
        console.log('  dataSize=' + dataSize + ': ' + count + ' accounts');
        if (count > 0 && count <= 20 && walletBytes32) {
          for (let i = 0; i < accounts.length; i++) {
            const acc = accounts[i];
            const dataRaw = acc.account?.data;
            const dataB64 = typeof dataRaw === 'string' ? dataRaw : (Array.isArray(dataRaw) ? Buffer.from(dataRaw).toString('base64') : null);
            if (!dataB64) continue;
            const buf = Buffer.from(dataB64, 'base64');
            const at0 = buf.length >= 32 && buf.slice(0, 32).equals(walletBytes32);
            const at8 = buf.length >= 40 && buf.slice(8, 40).equals(walletBytes32);
            if (at0 || at8) {
              const p = parseAccountData(dataB64);
              console.log('    [WALLET MATCH] pubkey=' + acc.pubkey + ' owner@' + (at0 ? '0' : '8') + ' amount@32=' + p.amountAt32 + ' amount@40=' + p.amountAt40);
            }
          }
        }
      } catch (e) {
        console.log('  dataSize=' + dataSize + ': error ' + e.message);
      }
    }

    // 1c) Альтернатива RPC: getProgramAccounts(Token) mint=SKR, delegate=Guardian @ offset 76; фильтр owner=wallet, сумма amount
    // SPL: mint@0, owner@32, amount@64, delegate Option 4b @72, delegate pubkey 32b @76
    console.log('\n--- getProgramAccounts Token: mint=SKR AND delegate=Guardian (offset 76), filter owner=wallet ---');
    const DELEGATE_OFFSET = 76;
    let stakedViaGetProgramAccounts = 0;
    let stakedViaGetProgramAccountsRaw = 0;
    try {
      const paramsTokenGuardian = [
        TOKEN_PROGRAM_ID,
        {
          encoding: 'base64',
          commitment: 'confirmed',
          filters: [
            { dataSize: TOKEN_ACCOUNT_DATA_SIZE },
            { memcmp: { offset: 0, bytes: SKR_MINT } },
            { memcmp: { offset: DELEGATE_OFFSET, bytes: SKR_STAKING_PROGRAM_ID } },
          ],
        },
      ];
      const tokenAccsWithGuardianDelegate = await rpc('getProgramAccounts', paramsTokenGuardian);
      const tokenList = tokenAccsWithGuardianDelegate || [];
      console.log('SKR token accounts with delegate=Guardian (getProgramAccounts):', tokenList.length);
      for (let i = 0; i < tokenList.length; i++) {
        const acc = tokenList[i];
        const dataRaw = acc.account?.data;
        const buf = typeof dataRaw === 'string' ? Buffer.from(dataRaw, 'base64') : (Array.isArray(dataRaw) && dataRaw[0] ? Buffer.from(String(dataRaw[0]), 'base64') : null);
        if (!buf || buf.length < 72) continue;
        const ownerBytes = buf.slice(TOKEN_ACCOUNT_OWNER_OFFSET, TOKEN_ACCOUNT_OWNER_OFFSET + 32);
        const amountRaw = readU64LE(buf, TOKEN_ACCOUNT_AMOUNT_OFFSET);
        if (walletBytes32 && ownerBytes.equals(walletBytes32)) {
          const human = amountRaw != null ? amountRaw / Math.pow(10, SKR_DECIMALS) : 0;
          stakedViaGetProgramAccountsRaw += amountRaw != null ? amountRaw : 0;
          stakedViaGetProgramAccounts += human;
          console.log('  [OUR WALLET] pubkey=' + acc.pubkey + ' amount(raw)=' + amountRaw + ' human=' + human);
        }
      }
      if (stakedViaGetProgramAccounts > 0) {
        console.log('  >>> STAKED SKR (getProgramAccounts Token): raw=' + stakedViaGetProgramAccountsRaw + ' human=' + stakedViaGetProgramAccounts);
      }
    } catch (e) {
      console.log('  Error:', e.message);
    }

    // 2e) Токен-аккаунты SKR, где delegate = наш кошелёк (стейк через SPL delegate)
    console.log('\n--- getProgramAccounts Token: mint=SKR AND delegate=wallet ---');
    try {
      const delegateOffset = 76; // SPL: mint 32, owner 32, amount 8, delegate Option 4+32 → pubkey at 76
      const delegateParams = [
        TOKEN_PROGRAM_ID,
        {
          encoding: 'base64',
          commitment: 'confirmed',
          filters: [
            { dataSize: TOKEN_ACCOUNT_DATA_SIZE },
            { memcmp: { offset: 0, bytes: SKR_MINT } },
            { memcmp: { offset: delegateOffset, bytes: walletFilter } },
          ],
        },
      ];
      const byDelegate = await rpc('getProgramAccounts', delegateParams);
      const delegateList = byDelegate || [];
      console.log('Token accounts (mint=SKR, delegate=wallet):', delegateList.length);
      let totalDelegated = 0;
      for (const acc of delegateList) {
        const dataB64 = typeof acc.account?.data === 'string' ? acc.account.data : (Array.isArray(acc.account?.data) ? Buffer.from(acc.account.data).toString('base64') : null);
        if (!dataB64) continue;
        const buf = Buffer.from(dataB64, 'base64');
        const amountRaw = readU64LE(buf, TOKEN_ACCOUNT_AMOUNT_OFFSET);
        const human = amountRaw != null ? amountRaw / Math.pow(10, SKR_DECIMALS) : 0;
        totalDelegated += human;
        console.log('  tokenAcc=' + acc.pubkey + ' amount=' + human + ' (owner is token account owner, delegate=wallet)');
      }
      if (delegateList.length > 0) console.log('  TOTAL delegated (staked) SKR:', totalDelegated);
    } catch (e) {
      console.log('Error:', e.message);
    }

    // 2f) Все SPL token-аккаунты с mint=SKR: ищем владельца = наш кошелёк или PDA Guardian
    console.log('\n--- All SKR token accounts (Token Program), find owner=wallet or PDA ---');
    try {
      const tokenAccsParams = [
        TOKEN_PROGRAM_ID,
        {
          encoding: 'base64',
          commitment: 'confirmed',
          filters: [
            { dataSize: TOKEN_ACCOUNT_DATA_SIZE },
            { memcmp: { offset: 0, bytes: SKR_MINT } },
          ],
        },
      ];
      const skrTokenAccounts = await rpc('getProgramAccounts', tokenAccsParams);
      const list = skrTokenAccounts || [];
      console.log('Total SKR token accounts (mint=SKR):', list.length);
      if (list.length > 0) {
        const first = list[0];
        const acc = first.account;
        const dr = acc?.data;
        const keys = acc ? Object.keys(acc) : [];
        console.log('  Sample account keys:', keys.join(', '));
        if (Array.isArray(dr)) {
          console.log('  data[0]=', typeof dr[0], String(dr[0]).slice(0, 30));
          console.log('  data[1]=', typeof dr[1], String(dr[1]).length, 'chars');
        } else if (typeof dr === 'string') {
          console.log('  data (string) length=', dr.length);
        }
      }
      function toTokenAccountBuffer(dataRaw) {
        if (typeof dataRaw === 'string') return Buffer.from(dataRaw, 'base64');
        if (Array.isArray(dataRaw)) {
          if (dataRaw.length >= 1 && typeof dataRaw[0] === 'string') return Buffer.from(dataRaw[0], 'base64');
          return Buffer.from(dataRaw);
        }
        return null;
      }
      let walletOwned = [];
      let pdaOwned = [];
      let stakedFromFullScan = []; // owner=wallet AND delegate@76=Guardian (SPL staking)
      const guardianPdaPubkeys = [];
      if (PublicKey && walletBytes32) {
        const programId = new PublicKey(SKR_STAKING_PROGRAM_ID);
        const seedsList = [
          [Buffer.from('stake'), walletBytes32],
          [Buffer.from('user_stake'), walletBytes32],
          [Buffer.from('user'), walletBytes32],
          [Buffer.from('stake_account'), walletBytes32],
        ];
        for (const seeds of seedsList) {
          try {
            const [pda] = PublicKey.findProgramAddressSync(seeds, programId);
            guardianPdaPubkeys.push(pda.toBuffer());
          } catch (_) {}
        }
      }
      for (let i = 0; i < list.length; i++) {
        const acc = list[i];
        const dataRaw = acc.account?.data;
        const buf = toTokenAccountBuffer(dataRaw);
        if (!buf || buf.length < TOKEN_ACCOUNT_AMOUNT_OFFSET + 8) continue;
        const ownerBytes = buf.slice(TOKEN_ACCOUNT_OWNER_OFFSET, TOKEN_ACCOUNT_OWNER_OFFSET + 32);
        const amountRaw = readU64LE(buf, TOKEN_ACCOUNT_AMOUNT_OFFSET);
        const amountHuman = amountRaw != null ? amountRaw / Math.pow(10, SKR_DECIMALS) : null;
        const ownerB58 = PublicKey ? new PublicKey(ownerBytes).toBase58() : ownerBytes.toString('hex').slice(0, 16) + '...';
        if (walletBytes32 && ownerBytes.equals(walletBytes32)) {
          walletOwned.push({ pubkey: acc.pubkey, amountRaw, amountHuman });
        }
        if (guardianPdaPubkeys.some((pda) => pda.equals(ownerBytes))) {
          pdaOwned.push({ pubkey: acc.pubkey, owner: ownerB58, amountRaw, amountHuman });
        }
        if (guardianBytes32 && buf.length >= 108 && walletBytes32 && ownerBytes.equals(walletBytes32)) {
          const delegateAt76 = buf.slice(76, 108);
          const delegateAt73 = buf.slice(73, 105);
          if (delegateAt76.equals(guardianBytes32) || delegateAt73.equals(guardianBytes32)) {
            stakedFromFullScan.push({ pubkey: acc.pubkey, amountRaw, amountHuman });
          }
        }
        if (i < 3 && guardianBytes32 && buf.length >= 108) {
          const opt72 = buf[72];
          const delegate76 = buf.slice(76, 108);
          const eq76 = delegate76.equals(guardianBytes32);
          console.log('  [sample ' + i + '] owner=' + ownerB58.slice(0, 12) + '.. opt@72=' + opt72 + ' delegate@76==Guardian=' + eq76);
        }
        if (i < 12) {
          console.log('  [' + i + '] tokenAcc=' + acc.pubkey + ' owner=' + ownerB58 + ' amount=' + amountHuman);
        }
      }
      if (walletOwned.length > 0) {
        console.log('  >>> OUR WALLET owns ' + walletOwned.length + ' SKR token account(s):', walletOwned.map((a) => a.pubkey + ' amount=' + a.amountHuman).join(', '));
      }
      if (pdaOwned.length > 0) {
        console.log('  >>> GUARDIAN PDA owns ' + pdaOwned.length + ' SKR token account(s) (stake):', pdaOwned.map((a) => a.pubkey + ' amount=' + a.amountHuman).join(', '));
      }
      if (stakedFromFullScan.length > 0) {
        const totalStaked = stakedFromFullScan.reduce((s, a) => s + (a.amountHuman ?? 0), 0);
        const totalStakedRaw = stakedFromFullScan.reduce((s, a) => s + (a.amountRaw ?? 0), 0);
        console.log('  >>> STAKED SKR (full scan: owner=wallet, delegate@76=Guardian):', stakedFromFullScan.length, 'account(s) total human=' + totalStaked + ' raw=' + totalStakedRaw);
        stakedFromFullScan.forEach((a, idx) => console.log('    [' + idx + '] ' + a.pubkey + ' amount=' + a.amountHuman));
      }
      if (walletOwned.length === 0 && pdaOwned.length === 0 && stakedFromFullScan.length === 0 && list.length > 0) {
        console.log('  (No account owned by wallet or by Guardian PDA; no delegate=Guardian+owner=wallet; sample owners above)');
      }
    } catch (e) {
      console.log('Error:', e.message);
    }

    // 3) PDA: стейк может храниться в аккаунте по адресу PDA(programId, [seed, wallet])
    if (PublicKey && walletBytes32) {
      console.log('\n--- PDA getAccountInfo (Guardian program, seeds: stake/user + wallet) ---');
      const programId = new PublicKey(SKR_STAKING_PROGRAM_ID);
      const seedsList = [
        [Buffer.from('stake'), walletBytes32],
        [Buffer.from('user_stake'), walletBytes32],
        [Buffer.from('user'), walletBytes32],
        [Buffer.from('stake_account'), walletBytes32],
        [Buffer.from('stake'), walletBytes32],
        [walletBytes32],
      ];
      for (const seeds of seedsList) {
        try {
          const [pda] = PublicKey.findProgramAddressSync(seeds, programId);
          const res = await rpc('getAccountInfo', [pda.toBase58(), { encoding: 'base64', commitment: 'confirmed' }]);
          const acc = res?.value;
          const label = seeds.map((s) => (s.length <= 20 ? s.toString() : s.toString('hex').slice(0, 12) + '..')).join('+');
          if (!acc) {
            console.log('  PDA [' + label + '] -> ' + pda.toBase58() + ' (no account)');
            continue;
          }
          const dataB64 = acc.data;
          const dataStr = typeof dataB64 === 'string' ? dataB64 : (Array.isArray(dataB64) ? Buffer.from(dataB64).toString('base64') : null);
          const buf = dataStr ? Buffer.from(dataStr, 'base64') : null;
          console.log('  PDA [' + label + '] -> ' + pda.toBase58() + ' data.length=' + (buf ? buf.length : 0));
          if (buf && buf.length >= 40 && dataStr) {
            const p = parseAccountData(dataStr);
            console.log('    hex[0..80]:', p.hex80);
            console.log('    amount@32:', p.amountAt32, 'human:', p.humanAt32, 'amount@40:', p.amountAt40, 'human:', p.humanAt40);
          }
        } catch (err) {
          console.log('  PDA error:', err.message);
        }
      }
    }

    // 4) История транзакций кошелька — какие программы вызывались (ищем стейк-программу)
    console.log('\n--- getSignaturesForAddress (wallet, limit=20) ---');
    const sigs = await rpc('getSignaturesForAddress', [walletFilter, { limit: 20, commitment: 'confirmed' }]);
    console.log('Recent signatures:', sigs?.length ?? 0);
    const programIds = new Set();
    for (let i = 0; i < Math.min(5, sigs?.length ?? 0); i++) {
      const sig = sigs[i];
      const tx = await rpc('getTransaction', [sig.signature, { encoding: 'jsonParsed', maxSupportedTransactionVersion: 0 }]);
      const meta = tx?.meta;
      const msg = tx?.transaction?.message;
      if (msg?.accountKeys) {
        for (const k of msg.accountKeys) {
          const pk = typeof k === 'string' ? k : k.pubkey;
          if (pk && pk.length > 30) programIds.add(pk);
        }
      }
      if (tx?.meta?.err) continue;
      if (i === 0 && tx?.transaction?.message?.accountKeys) {
        console.log('  Sample tx accountKeys count:', tx.transaction.message.accountKeys.length);
      }
    }
    const guardianInTx = programIds.has(SKR_STAKING_PROGRAM_ID);
    console.log('  Guardian program in recent tx:', guardianInTx);

    // 5) Крупнейшие держатели SKR (могут быть пулы/стейк-контракты)
    console.log('\n--- getTokenLargestAccounts (mint=SKR) ---');
    const largest = await rpc('getTokenLargestAccounts', [SKR_MINT, { commitment: 'confirmed' }]);
    const topAccounts = largest?.value ?? [];
    console.log('Top accounts:', topAccounts.length);
    for (let i = 0; i < Math.min(10, topAccounts.length); i++) {
      const v = topAccounts[i];
      const ui = Number(v.amount || 0) / Math.pow(10, SKR_DECIMALS);
      console.log('  [' + i + '] address=' + v.address + ' amount=' + ui.toLocaleString());
    }
  } catch (e) {
    console.error('Error:', e.message);
    if (e.cause) console.error('Cause:', e.cause);
    process.exit(1);
  }
}

main();
