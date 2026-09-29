#!/usr/bin/env python3
from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1]
rows=[line.split('\t') for line in (root/'tools/ui-translations.tsv').read_text().splitlines() if line]
assert all(len(row)==4 and all(row) for row in rows)
keys={row[0] for row in rows}
assert len(keys)==len(rows)
for row in rows:
    formats=[re.findall(r'%(?:\d+\$)?[-+0,#\d.]*[a-zA-Z%]',v) for v in row[1:]]
    assert formats[0]==formats[1]==formats[2],(row[0],formats)
legacy={line.split('\t')[0].replace('\\n','\n') for line in (root/'tools/status-translations.tsv').read_text().splitlines()}
for p in (root/'app/src/main/java/ua/iben/recorder').glob('*.java'):
    if p.name=='I18n.java':continue
    source=p.read_text()
    for key in re.findall(r'I18n\.s\("([^"]+)"',source):
        if key=='day_':assert all('day_'+str(i) in keys for i in range(7))
        else: assert key in keys,(p.name,key)
    for literal in re.findall(r'"(?:[^"\\]|\\.)*"',source):
        if re.search('[А-Яа-яІіЇїЄєҐґ]',literal):
            value=json.loads(literal)
            assert value in legacy or value in {'Українська',' с'},(p.name,value)
print('PASS: Ukrainian / English / Polish coverage and formatting; service messages mapped')
