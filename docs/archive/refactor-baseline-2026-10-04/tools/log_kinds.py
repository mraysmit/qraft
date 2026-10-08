import re, sys, collections
def norm(line):
    line = line.rstrip('\r\n')
    m = re.match(r'^[0-9:.]+ \[([^\]]+)\] (ERROR|WARN) +(\S+)(.*)$', line)
    if not m: return None
    lvl, logger, rest = m.group(2), m.group(3), m.group(4)
    rest = re.sub(r'^( \[[^\]]*\])*', '', rest)          # MDC groups
    rest = re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27}', '<uuid>', rest)
    rest = re.sub(r'\d+', '#', rest)
    rest = re.sub(r'([A-Za-z]:)?[\\/][^ ,)]*', '<path>', rest)
    return f'{lvl} {logger}{rest[:160]}'
def exc(line):
    line=line.rstrip('\r\n')
    m = re.match(r'^(Caused by: )?([a-zA-Z0-9_.$]+(Exception|Error))(: (.*))?$', line)
    if not m: return None
    msg = re.sub(r'\d+', '#', (m.group(5) or ''))[:100]
    return f'{m.group(1) or ""}{m.group(2)}: {msg}'
kind = sys.argv[1]
c = collections.Counter()
for l in open(sys.argv[2], encoding='utf-8', errors='replace'):
    k = norm(l) if kind == 'log' else exc(l)
    if k: c[k] += 1
for k, v in sorted(c.items()): print(f'{v}\t{k}')
