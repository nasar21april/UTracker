import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

bad_string = """            // Draw Daily Candlestick on the right
            if (dailyOhlc != null) {// Draw Daily Candlestick on the right
            if (dailyOhlc != null) {"""

good_string = """            // Draw Daily Candlestick on the right
            if (dailyOhlc != null) {"""

if bad_string in content:
    content = content.replace(bad_string, good_string)
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Fixed bad string.")
else:
    print("Bad string not found!")

