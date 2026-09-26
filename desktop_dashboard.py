#!/usr/bin/env python3
"""
UTracker Desktop Server & Live Dashboard
Bridges live Upstox data, auto-fetches tokens from Firebase,
provides REST endpoints for Market Quotes, Option Chain, & Historical Candles,
and serves the UTracker Desktop Trading Dashboard on http://localhost:8080.
"""

import http.server
import socketserver
import urllib.request
import urllib.error
import ssl
import json
import os
import sys
import threading
import webbrowser
import time

PORT = 8080
FIREBASE_URL = "https://swingscreener-3f83f-default-rtdb.asia-southeast1.firebasedatabase.app/upstox_token.json"
USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

# In-memory virtual ledger state
ledger_trades = []
cached_token = ""
last_token_fetch = 0

ctx = ssl._create_unverified_context()

def get_live_token():
    global cached_token, last_token_fetch
    now = time.time()
    # Cache token for 60 seconds
    if cached_token and (now - last_token_fetch) < 60:
        return cached_token

    try:
        req = urllib.request.Request(FIREBASE_URL)
        req.add_header("User-Agent", USER_AGENT)
        with urllib.request.urlopen(req, context=ctx, timeout=5) as r:
            data = json.loads(r.read().decode("utf-8"))
            if data and "token" in data:
                cached_token = data["token"]
                last_token_fetch = now
                return cached_token
    except Exception as e:
        print(f"[!] Error fetching token from Firebase: {e}")
    return cached_token

def upstox_api_request(endpoint, token=None):
    if not token:
        token = get_live_token()
    if not token:
        return {"status": "error", "message": "No Upstox token available"}

    url = f"https://api.upstox.com/v2/{endpoint}"
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", USER_AGENT)

    try:
        with urllib.request.urlopen(req, context=ctx, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            err_data = json.loads(e.read().decode("utf-8"))
            return {"status": "error", "code": e.code, "error": err_data}
        except:
            return {"status": "error", "code": e.code, "message": str(e)}
    except Exception as e:
        return {"status": "error", "message": str(e)}

class UTrackerHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        global ledger_trades
        if self.path == "/" or self.path.startswith("/?"):
            self.serve_dashboard()
        elif self.path.startswith("/api/quotes"):
            self.handle_quotes()
        elif self.path.startswith("/api/candles"):
            self.handle_candles()
        elif self.path.startswith("/api/trades"):
            self.send_json_response(200, {"status": "success", "trades": ledger_trades})
        elif self.path.startswith("/api/token"):
            token = get_live_token()
            self.send_json_response(200, {"status": "success", "has_token": bool(token), "token_preview": token[:15] + "..." if token else "None"})
        else:
            super().do_GET()

    def do_POST(self):
        global ledger_trades, cached_token
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        try:
            data = json.loads(body)
        except:
            data = {}

        if self.path == "/api/trades/execute":
            # Add new trade to ledger
            trade = {
                "id": str(int(time.time() * 1000)),
                "indexSymbol": data.get("symbol", "NIFTY"),
                "optionType": data.get("optionType", "CE"),
                "strikePrice": float(data.get("strikePrice", 0.0)),
                "action": data.get("action", "BUY"),
                "quantity": int(data.get("quantity", 50)),
                "entryPrice": float(data.get("entryPrice", 0.0)),
                "currentPrice": float(data.get("entryPrice", 0.0)),
                "isClosed": False,
                "exitPrice": 0.0,
                "timestamp": int(time.time() * 1000)
            }
            ledger_trades.append(trade)
            self.send_json_response(200, {"status": "success", "trade": trade, "trades": ledger_trades})

        elif self.path == "/api/trades/clear":
            # RESET LEDGER
            ledger_trades.clear()
            print("[+] Ledger cleared successfully via Desktop UI.")
            self.send_json_response(200, {"status": "success", "message": "Ledger cleared", "trades": []})

        elif self.path == "/api/trades/squareoff":
            for t in ledger_trades:
                if not t.get("isClosed"):
                    t["isClosed"] = True
                    t["exitPrice"] = t.get("currentPrice", t.get("entryPrice", 0.0))
            self.send_json_response(200, {"status": "success", "trades": ledger_trades})

        elif self.path == "/api/token/update":
            new_tok = data.get("token", "").strip()
            if new_tok:
                cached_token = new_tok
                self.send_json_response(200, {"status": "success", "message": "Token updated"})
            else:
                self.send_json_response(400, {"status": "error", "message": "Empty token"})
        else:
            self.send_json_response(404, {"status": "not_found"})

    def handle_quotes(self):
        # Fetch quotes for indices
        keys = "NSE_INDEX|Nifty 50,NSE_INDEX|Nifty Bank,BSE_INDEX|SENSEX"
        res = upstox_api_request(f"market-quote/quotes?instrument_key={keys}")
        self.send_json_response(200, res)

    def handle_candles(self):
        # /api/candles?symbol=NIFTY
        sym = "NIFTY"
        if "symbol=BANKNIFTY" in self.path:
            inst = "NSE_INDEX|Nifty Bank"
            sym = "BANKNIFTY"
        elif "symbol=SENSEX" in self.path:
            inst = "BSE_INDEX|SENSEX"
            sym = "SENSEX"
        else:
            inst = "NSE_INDEX|Nifty 50"
            sym = "NIFTY"

        # 1-minute intraday candles
        res = upstox_api_request(f"historical-candle/intraday/{urllib.parse.quote(inst)}/1minute")
        self.send_json_response(200, res)

    def send_json_response(self, code, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def serve_dashboard(self):
        html_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "desktop_dashboard.html")
        if os.path.exists(html_file):
            with open(html_file, "r", encoding="utf-8") as f:
                content = f.read().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        else:
            self.send_response(404)
            self.end_headers()

def run_server():
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", PORT), UTrackerHandler) as httpd:
        print(f"============================================================")
        print(f"  UTracker Desktop Live Dashboard running on:")
        print(f"  👉 http://localhost:{PORT}")
        print(f"============================================================")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down desktop server...")

if __name__ == "__main__":
    t = threading.Thread(target=run_server, daemon=True)
    t.start()
    time.sleep(0.5)
    webbrowser.open(f"http://localhost:{PORT}")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        sys.exit(0)
