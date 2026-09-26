import re

file_path = 'app/src/main/java/com/example/upstoxmarketdataapp/ui/feed/FeedScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace fully qualified compose prefixes in the VirtualLedgerTable
content = content.replace('androidx.compose.foundation.layout.', '')
content = content.replace('androidx.compose.foundation.', '')
content = content.replace('androidx.compose.ui.draw.', '')
content = content.replace('androidx.compose.ui.graphics.', '')
content = content.replace('androidx.compose.ui.text.font.', '')
content = content.replace('androidx.compose.ui.', '')
content = content.replace('androidx.compose.material3.', '')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed")
