import os

def replace_in_file(file_path, replacements):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    for old, new in replacements:
        content = content.replace(old, new)
        
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)

replacements = [
    ('"ATM - 2"', '"2 Above"'),
    ('"ATM - 1"', '"1 Above"'),
    ('"ATM + 1"', '"1 Below"'),
    ('"ATM + 2"', '"2 Below"'),
    ('"ATM"', '"Current"'),
    ('_ATM', '_Current'),
    ('"$strikeVal Current"', '"$strikeVal ATM"') # Fix the side effect
]

files = [
    r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\data\InstrumentRepository.kt',
    r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\data\UpstoxService.kt',
    r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'
]

for file_path in files:
    replace_in_file(file_path, replacements)
    print(f"Rolled back strings in {file_path}")
