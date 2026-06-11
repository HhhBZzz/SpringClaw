#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import ssl
import sys
import urllib.parse
import urllib.request


COIN_IDS = {
    "BTC": "bitcoin",
    "ETH": "ethereum",
    "SOL": "solana",
    "BNB": "binancecoin",
    "DOGE": "dogecoin",
    "XRP": "ripple",
    "ADA": "cardano",
    "AVAX": "avalanche-2",
    "DOT": "polkadot",
    "LINK": "chainlink",
}

# Gate.io trading pair mapping: symbol → gate_pair
GATE_PAIRS = {
    "BTC": "btc_usdt",
    "ETH": "eth_usdt",
    "SOL": "sol_usdt",
    "BNB": "bnb_usdt",
    "DOGE": "doge_usdt",
    "XRP": "xrp_usdt",
    "ADA": "ada_usdt",
    "AVAX": "avax_usdt",
    "DOT": "dot_usdt",
    "LINK": "link_usdt",
}

# CNY exchange rate (approximate, will be fetched live if possible)
CNY_PER_USD = 7.25

CN_ALIASES = {
    "比特币": "BTC",
    "以太坊": "ETH",
    "索拉纳": "SOL",
    "安币": "BNB",
    "狗狗币": "DOGE",
    "瑞波币": "XRP",
    "艾达币": "ADA",
    "雪崩币": "AVAX",
    "波卡": "DOT",
    "链克": "LINK",
    "币": "BTC",
    "以太": "ETH",
}


def _resolve_cn_aliases(text: str) -> list[str]:
    found = []
    for cn_name, symbol in CN_ALIASES.items():
        if cn_name in text:
            if symbol not in found:
                found.append(symbol)
    return found


def _build_ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    return ctx


def _get_proxy_handler() -> urllib.request.ProxyHandler | None:
    http_proxy = os.environ.get("http_proxy") or os.environ.get("HTTP_PROXY")
    https_proxy = os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
    if http_proxy or https_proxy:
        proxies = {}
        if http_proxy:
            proxies["http"] = http_proxy
        if https_proxy:
            proxies["https"] = https_proxy
        return urllib.request.ProxyHandler(proxies)
    return None


def _make_opener() -> urllib.request.OpenerDirector:
    handlers = []
    proxy_handler = _get_proxy_handler()
    if proxy_handler:
        handlers.append(proxy_handler)
    handlers.append(urllib.request.HTTPSHandler(context=_build_ssl_context()))
    return urllib.request.build_opener(*handlers)


def _fetch_cny_rate() -> float:
    """Fetch live USD→CNY rate from open.er-api.com (accessible in China)."""
    try:
        opener = _make_opener()
        request = urllib.request.Request(
            "https://open.er-api.com/v6/latest/USD",
            headers={"User-Agent": "SpringClawSkill/1.0"},
        )
        with opener.open(request, timeout=5) as response:
            data = json.loads(response.read().decode("utf-8"))
            return float(data.get("rates", {}).get("CNY", CNY_PER_USD))
    except Exception:
        return CNY_PER_USD


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"symbols": str(payload)}
    except Exception:
        return {"symbols": raw}


def parse_symbols(value) -> list[str]:
    raw_text = str(value or "").strip()
    if not raw_text:
        return ["BTC", "ETH"]

    # Resolve Chinese aliases first (e.g. "比特币" → BTC)
    cn_resolved = _resolve_cn_aliases(raw_text)
    if cn_resolved:
        raw_symbols = raw_text.replace("，", ",").split(",")
        symbols = []
        for raw in raw_symbols + cn_resolved:
            symbol = str(raw).strip().upper()
            if symbol and symbol not in symbols:
                symbols.append(symbol)
        return symbols or ["BTC", "ETH"]

    if isinstance(value, list):
        raw_symbols = value
    else:
        raw_symbols = raw_text.replace("，", ",").split(",")
    symbols = []
    for raw in raw_symbols:
        symbol = str(raw).strip().upper()
        if symbol and symbol not in symbols:
            symbols.append(symbol)
    return symbols or ["BTC", "ETH"]


def fetch_prices_gateio(symbols: list[str]) -> dict | None:
    """Primary: Gate.io API — accessible in mainland China, no auth required."""
    pairs = {s: GATE_PAIRS[s] for s in symbols if s in GATE_PAIRS}
    if not pairs:
        return None
    results = {}
    opener = _make_opener()
    for symbol, pair in pairs.items():
        try:
            url = f"https://data.gateapi.io/api2/1/ticker/{pair}"
            request = urllib.request.Request(url, headers={"User-Agent": "SpringClawSkill/1.0"})
            with opener.open(request, timeout=8) as response:
                data = json.loads(response.read().decode("utf-8"))
                last_price = float(data.get("last", 0) or 0)
                change_pct = float(data.get("percentChange", 0) or 0)
                if last_price > 0:
                    coin_id = COIN_IDS[symbol]
                    results[coin_id] = {
                        "usd": last_price,
                        "usd_24h_change": change_pct,
                    }
        except Exception:
            continue
    return results if results else None


def fetch_prices_coingecko(symbols: list[str]) -> dict | None:
    """Fallback 1: CoinGecko API (may be unreachable in China)."""
    ids = [COIN_IDS[symbol] for symbol in symbols if symbol in COIN_IDS]
    if not ids:
        return None
    query = urllib.parse.urlencode({
        "ids": ",".join(ids),
        "vs_currencies": "usd,cny",
        "include_24hr_change": "true",
    })
    request = urllib.request.Request(
        "https://api.coingecko.com/api/v3/simple/price?" + query,
        headers={"User-Agent": "SpringClawSkill/1.0"},
    )
    opener = _make_opener()
    try:
        with opener.open(request, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except Exception:
        return None


def fetch_prices(symbols: list[str]) -> dict | None:
    """Gate.io (China-accessible) → CoinGecko (international fallback)."""
    result = fetch_prices_gateio(symbols)
    if result:
        return result
    return fetch_prices_coingecko(symbols)


def main() -> None:
    payload = parse_payload()
    symbols = parse_symbols(payload.get("symbols") or payload.get("symbol") or payload.get("goal"))
    dry_run = bool(payload.get("dryRun") or payload.get("dry_run"))
    unsupported = [symbol for symbol in symbols if symbol not in COIN_IDS]
    supported = [symbol for symbol in symbols if symbol in COIN_IDS]

    lines = [
        "skill=crypto_price",
        f"symbols={','.join(symbols)}",
        f"dryRun={str(dry_run).lower()}",
    ]
    if unsupported:
        lines.append(f"unsupported={','.join(unsupported)}")
    if dry_run:
        lines.append(f"wouldFetch={','.join(COIN_IDS[symbol] for symbol in supported)}")
        print("\n".join(lines))
        return

    prices = fetch_prices(symbols)
    if prices:
        cny_rate = _fetch_cny_rate()
        by_coin_id = {coin_id: symbol for symbol, coin_id in COIN_IDS.items()}
        for coin_id, data in prices.items():
            symbol = by_coin_id.get(coin_id, coin_id.upper())
            usd = data.get("usd")
            cny = data.get("cny")
            change = data.get("usd_24h_change")
            # Gate.io returns USD price; compute CNY if not provided
            if cny is None and usd is not None:
                cny = round(float(usd) * cny_rate, 2)
            change_text = f", 24h={change:.2f}%" if isinstance(change, (int, float)) else ""
            lines.append(f"- {symbol}: usd={usd}, cny={cny}{change_text}")
        print("\n".join(lines))
    else:
        lines.extend(["status=failed", "error=All crypto APIs unreachable from this network"])
        print("\n".join(lines))


if __name__ == "__main__":
    main()