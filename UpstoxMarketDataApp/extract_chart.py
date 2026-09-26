import os

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

start_idx = -1
for i, line in enumerate(lines):
    if 'fun DualLayerIndexChart' in line:
        start_idx = i
        break

if start_idx != -1:
    with open('extract_chart.txt', 'w', encoding='utf-8') as f:
        f.writelines(lines[start_idx:start_idx+350])
