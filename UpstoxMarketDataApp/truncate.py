import os

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

print(f"Original length: {len(lines)}")
# Find the exact line of `val candles15s = index15sCandles[sym] ?: emptyList()`
target_index = -1
for i, line in enumerate(lines):
    if "val candles15s = index15sCandles[sym] ?: emptyList()" in line:
        target_index = i
        break

if target_index != -1:
    print(f"Found dangling code at line {target_index + 1}. Truncating...")
    new_lines = lines[:target_index]
    # Remove trailing empty lines and trailing '}' that might be unmatched
    # Wait! The NEW GlobalTradeButtonsRow must end with `}`.
    # So I will just slice up to target_index and then write it back.
    with open(file_path, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
    print("Done.")
else:
    print("Dangling code not found.")
