import json

transcript_path = r'C:\Users\5012001749\.gemini\antigravity\brain\78ce46cc-ae5e-4624-b753-0c33a1a0fd94\.system_generated\logs\transcript.jsonl'

output = []
try:
    with open(transcript_path, 'r', encoding='utf-8') as f:
        for line in f:
            if 'fun GlobalTradeButtonsRow' in line and '@Composable' in line:
                try:
                    data = json.loads(line)
                    # Look inside content or tool_calls
                    output.append(line)
                except:
                    pass
except Exception as e:
    print(f"Error: {e}")

with open('find_history.txt', 'w', encoding='utf-8') as f:
    for o in output:
        f.write(o + '\n')
