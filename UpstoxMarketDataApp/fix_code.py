import re

file_path = 'app/src/main/java/com/example/upstoxmarketdataapp/ui/feed/FeedScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('geometry.Offset', 'Offset')
content = content.replace('graphics.Color', 'Color')
content = content.replace('graphics.PathEffect', 'PathEffect')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed")
