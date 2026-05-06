#!/usr/bin/env python3
from __future__ import annotations

import json
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


def parse_payload() -> dict:
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {"symbols": str(payload)}
    except Exception:
        return {"symbols": raw}


def parse_symbols(value) -> list[str]:
    if isinstance(value, list):
        raw_symbols = value
    else:
        raw_symbols = str(value or "BTC,ETH").replace("，", ",").split(",")
    symbols = []
    for raw in raw_symbols:
        symbol = str(raw).strip().upper()
        if symbol and symbol not in symbols:
            symbols.append(symbol)
    return symbols or ["BTC", "ETH"]


def fetch_prices(symbols: list[str]) -> dict:
    ids = [COIN_IDS[symbol] for symbol in symbols if symbol in COIN_IDS]
    if not ids:
        raise ValueError("no supported symbols")
    query = urllib.parse.urlencode({
        "ids": ",".join(ids),
        "vs_currencies": "usd,cny",
        "include_24hr_change": "true",
    })
    request = urllib.request.Request(
        "https://api.coingecko.com/api/v3/simple/price?" + query,
        headers={"User-Agent": "SpringClawSkill/1.0"},
    )
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.loads(response.read().decode("utf-8"))


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

    try:
        prices = fetch_prices(symbols)
        by_coin_id = {coin_id: symbol for symbol, coin_id in COIN_IDS.items()}
        for coin_id, data in prices.items():
            symbol = by_coin_id.get(coin_id, coin_id.upper())
            usd = data.get("usd")
            cny = data.get("cny")
            change = data.get("usd_24h_change")
            change_text = f", 24h={change:.2f}%" if isinstance(change, (int, float)) else ""
            lines.append(f"- {symbol}: usd={usd}, cny={cny}{change_text}")
        print("\n".join(lines))
    except Exception as exc:
        lines.extend(["status=failed", f"error={exc}"])
        print("\n".join(lines))


if __name__ == "__main__":
    main()
