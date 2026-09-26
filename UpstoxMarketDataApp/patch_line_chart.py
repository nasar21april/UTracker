import re

file_path = r'C:\Users\5012001749\Desktop\AI_LAB_2\UpstoxMarketDataApp\app\src\main\java\com\example\upstoxmarketdataapp\ui\feed\FeedScreen.kt'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update paddingRight
content = content.replace("val paddingRight = 130f", "val paddingRight = 180f")

# 2. Update Zero baseline
old_baseline = """        // Zero baseline
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(paddingLeft, zeroY),
            end = Offset(width - paddingRight, zeroY),
            strokeWidth = 1.5f
        )"""

new_baseline = """        // Zero baseline
        drawLine(
            color = if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
            start = Offset(paddingLeft, zeroY),
            end = Offset(width - paddingRight, zeroY),
            strokeWidth = 3f
        )"""

if old_baseline in content:
    content = content.replace(old_baseline, new_baseline)
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Patched FeedScreen.kt to compress line chart and make zero line bold.")
else:
    print("Error: Could not find old baseline logic!")
