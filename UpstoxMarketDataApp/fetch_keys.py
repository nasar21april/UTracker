import urllib.request, gzip, csv, io
url = 'https://assets.upstox.com/market-quote/instruments/exchange/complete.csv.gz'
response = urllib.request.urlopen(url)
compressed_file = io.BytesIO(response.read())
decompressed_file = gzip.GzipFile(fileobj=compressed_file)
csv_content = decompressed_file.read().decode('utf-8')

reader = csv.DictReader(io.StringIO(csv_content))
nifty_symbols = ['RELIANCE', 'TCS', 'HDFCBANK', 'ICICIBANK', 'BHARTIARTL', 'SBIN', 'INFY', 'LICI', 'ITC', 'HINDUNILVR', 'LT', 'BAJFINANCE', 'HCLTECH', 'MARUTI', 'SUNPHARMA', 'TATAMOTORS', 'M&M', 'TATASTEEL', 'POWERGRID', 'ULTRACEMCO', 'TITAN', 'NTPC', 'BAJAJFINSV', 'ASIANPAINT', 'KOTAKBANK']
results = []
for row in reader:
    if row['exchange'] == 'NSE_EQ' and row['tradingsymbol'] in nifty_symbols and row['instrument_type'] == 'EQUITY':
        results.append('        Pair("' + row['tradingsymbol'] + '", "' + row['instrument_key'] + '")')

print(',\n'.join(results))
