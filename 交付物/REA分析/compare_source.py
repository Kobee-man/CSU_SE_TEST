import sys, pathlib, re, json, difflib

def tokens(text):
    # Preserve string/character literals while dropping Java comments and spacing.
    pattern = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|\s+|[\w$]+|.'
    return [m.group() for m in re.finditer(pattern, text) if not (m.group().isspace() or m.group().startswith('//') or m.group().startswith('/*'))]

a,b=map(pathlib.Path,sys.argv[1:3])
x={str(p.relative_to(a)):p for p in a.rglob('*.java')}
y={str(p.relative_to(b)):p for p in b.rglob('*.java')}
out={'main_count':len(x),'supp_count':len(y),'same_tokens':[],'changed_tokens':[],'only_main':sorted(x.keys()-y.keys()),'only_supp':sorted(y.keys()-x.keys())}
for k in sorted(x.keys()&y.keys()):
    u,v=[tokens(p.read_text(encoding='utf-8-sig')) for p in [x[k],y[k]]]
    if u==v:out['same_tokens'].append(k)
    else:
        changes=[]
        for tag,i,j,l,m in difflib.SequenceMatcher(None,u,v,autojunk=False).get_opcodes():
            if tag!='equal':changes.append({'main':' '.join(u[i:j]),'supp':' '.join(v[l:m])})
        out['changed_tokens'].append({'file':k,'changes':changes})
print(json.dumps(out,ensure_ascii=False,indent=2))
