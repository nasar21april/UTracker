import re

file_path = 'app/src/main/java/com/example/upstoxmarketdataapp/ui/feed/FeedScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_lines = []
imports_done = False
for i, line in enumerate(lines):
    if line.startswith('import ') and not imports_done:
        continue
    if not line.startswith('import ') and 'package com' not in line and line.strip() != '':
        if not imports_done:
            imports_done = True
            new_lines.append('''import android.graphics.Paint
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import java.util.Locale
import com.example.upstoxmarketdataapp.R
import com.example.upstoxmarketdataapp.data.*
''')
    new_lines.append(line)

with open(file_path, 'w', encoding='utf-8') as f:
    f.writelines(new_lines)

# Fix geometry.Offset usage in code
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('geometry.Offset', 'Offset')
content = content.replace('graphics.Color', 'Color')
content = content.replace('graphics.PathEffect', 'PathEffect')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed")
