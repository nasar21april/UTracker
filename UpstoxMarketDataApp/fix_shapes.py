import re

file_path = 'app/src/main/java/com/example/upstoxmarketdataapp/ui/feed/FeedScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('shape.RoundedCornerShape', 'RoundedCornerShape')
content = content.replace('import RoundedCornerShape\n', '')
content = content.replace('androidx.compose.ui.graphics.Color', 'Color')
content = content.replace('androidx.compose.ui.text.font.FontWeight', 'FontWeight')
content = content.replace('androidx.compose.ui.Alignment', 'Alignment')
content = content.replace('androidx.compose.ui.Modifier', 'Modifier')
content = content.replace('androidx.compose.material3.Text', 'Text')
content = content.replace('androidx.compose.material3.MaterialTheme', 'MaterialTheme')
content = content.replace('androidx.compose.foundation.layout.Row', 'Row')
content = content.replace('androidx.compose.foundation.layout.Column', 'Column')
content = content.replace('androidx.compose.foundation.layout.Box', 'Box')
content = content.replace('androidx.compose.foundation.background', 'background')
content = content.replace('androidx.compose.foundation.border', 'border')
content = content.replace('androidx.compose.foundation.shape.RoundedCornerShape', 'RoundedCornerShape')

# Fix extension methods that might have been qualified
content = re.sub(r'Modifier\s*\.\s*androidx\.compose\.foundation\.layout\.fillMaxWidth\(\)', 'Modifier.fillMaxWidth()', content)
content = re.sub(r'Modifier\s*\.\s*androidx\.compose\.foundation\.layout\.height\(', 'Modifier.height(', content)
content = re.sub(r'Modifier\s*\.\s*androidx\.compose\.foundation\.layout\.padding\(', 'Modifier.padding(', content)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed")
