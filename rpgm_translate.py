#!/usr/bin/env python3
"""
rpgm_translate.py - extrai, traduz e reinsere textos de jogos RPG Maker.

Suporta:  MV / MZ (.json)   |   XP (.rxdata)   |   VX (.rvdata)   |   VX Ace (.rvdata2)
Nao suporta direto: RPG Maker 2000/2003 (.lmu/.ldb) e Wolf RPG (.wolf/.dat/.mps)
  -> o comando "detect" avisa e indica ferramentas externas para esses.

Fluxo (MV/MZ: rode antes `check-plugins PASTA_DO_JOGO` para ver riscos):
  1) python rpgm_translate.py extract  PASTA_DO_JOGO  -o strings.json
  2) python rpgm_translate.py translate strings.json --src ja --tgt en     (opcional)
     ...ou preencha o campo "tr" de cada entrada por outro meio (outro tradutor, a mao).
  3) python rpgm_translate.py inject   PASTA_DO_JOGO  strings.json -o PASTA_SAIDA

Seguranca:
  * Nunca altera a pasta original: "inject" copia a pasta data para a saida.
  * So traduz dialogos, escolhas, nomes/descricoes de banco de dados e termos do sistema.
    NAO toca em: nomes de switches/variaveis, imagens, audio, scripts, comentarios,
    comandos de plugin, notas (note) e nomes de eventos.
  * Codigos de controle (\\C[n], \\V[n], \\N[n], \\I[n], \\., \\|, \\! ...) sao conferidos:
    se a traducao perder ou alterar algum, a entrada e ignorada (mantem o original).
  * Cada entrada guarda o texto original; se o jogo mudou, a entrada e ignorada.
"""
import argparse, collections, copy, json, os, re, shutil, struct, sys, time
import urllib.parse, urllib.request

# ----------------------------------------------------------------------------
# Utilidades de texto
# ----------------------------------------------------------------------------
JP = re.compile(r'[\u3000-\u30ff\u3400-\u9fff\uff00-\uffef]')
CTRL = re.compile(r'\\(?:[A-Za-z]+\[[^\]]*\]|[A-Za-z]|[.|!><^{}$\\])')
LEAD = re.compile(r'^(?:\\(?:[A-Za-z]+\[[^\]]*\]|[A-Za-z]|[.|!><^{}$\\]))+')


def vis_len(s):
    return len(CTRL.sub('', s))


def codes_of(s):
    return collections.Counter(CTRL.findall(s))


def wrap_words(text, width):
    words, lines, cur = text.split(), [], ''
    for w in words:
        if not cur:
            cur = w
        elif vis_len(cur) + 1 + vis_len(w) <= width:
            cur += ' ' + w
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines or ['']


def layout(tr, width, chunk=None):
    """Quebra a traducao em linhas; se chunk, divide em janelas de ate `chunk` linhas."""
    lines = tr.split('\n')
    if width and any(vis_len(l) > width for l in lines):
        lines = wrap_words(' '.join(l.strip() for l in lines), width)
    if not chunk:
        return [lines]
    return [lines[i:i + chunk] for i in range(0, len(lines), chunk)] or [['']]


# ----------------------------------------------------------------------------
# Leitor / escritor de Ruby Marshal (formato 4.8) - usado por XP, VX e VX Ace
# ----------------------------------------------------------------------------
class N:
    """No da arvore Marshal. t: nil true false fix sym str arr hash obj struct float big
    user usrm regexp class module"""
    __slots__ = ('t', 'v', 'cls', 'iv', 'pre', 'x')

    def __init__(self, t, v=None, cls=None):
        self.t, self.v, self.cls, self.iv, self.pre, self.x = t, v, cls, [], [], None

    def ivar(self, name):
        for k, val in self.iv:
            if k == name:
                return val
        return None

    def text(self):
        try:
            return self.v.decode('utf-8')
        except UnicodeDecodeError:
            return self.v.decode('cp932', 'replace')

    def set(self, s):
        self.v = s.encode('utf-8')
        if self.iv and not s.isascii():
            self.iv = [('E', N('true'))]


class Reader:
    def __init__(self, data):
        if data[:2] != b'\x04\x08':
            raise ValueError('nao e Marshal 4.8')
        self.d, self.p, self.syms, self.objs = data, 2, [], []

    def byte(self):
        b = self.d[self.p]
        self.p += 1
        return b

    def long(self):
        c = struct.unpack('b', bytes([self.byte()]))[0]
        if c == 0:
            return 0
        if c > 0:
            if 4 < c < 128:
                return c - 5
            x = 0
            for i in range(c):
                x |= self.byte() << (8 * i)
            return x
        if -129 < c < -4:
            return c + 5
        c = -c
        x = -1
        for i in range(c):
            x &= ~(0xff << (8 * i))
            x |= self.byte() << (8 * i)
        return x

    def bytes_(self):
        n = self.long()
        b = self.d[self.p:self.p + n]
        self.p += n
        return b

    def sym(self):
        t = chr(self.byte())
        if t == ':':
            s = self.bytes_().decode('utf-8')
            self.syms.append(s)
            return s
        if t == ';':
            return self.syms[self.long()]
        raise ValueError('simbolo esperado, veio %r' % t)

    def reg(self, n):
        self.objs.append(n)
        return n

    def obj(self):
        c = chr(self.byte())
        if c == '0': return N('nil')
        if c == 'T': return N('true')
        if c == 'F': return N('false')
        if c == 'i': return N('fix', self.long())
        if c == ':':
            s = self.bytes_().decode('utf-8'); self.syms.append(s); return N('sym', s)
        if c == ';': return N('sym', self.syms[self.long()])
        if c == '@': return self.objs[self.long()]
        if c == 'I':
            inner = self.obj()
            for _ in range(self.long()):
                k = self.sym(); inner.iv.append((k, self.obj()))
            return inner
        if c == 'e':
            m = self.sym(); inner = self.obj(); inner.pre.insert(0, ('e', m)); return inner
        if c == 'C':
            k = self.sym(); inner = self.obj(); inner.pre.insert(0, ('C', k)); return inner
        if c == '"': return self.reg(N('str', self.bytes_()))
        if c == 'f': return self.reg(N('float', self.bytes_()))
        if c == 'l':
            sign = self.d[self.p:self.p + 1]; self.p += 1
            ln = self.long(); raw = self.d[self.p:self.p + ln * 2]; self.p += ln * 2
            return self.reg(N('big', (sign, ln, raw)))
        if c == '[':
            n = self.reg(N('arr', [])); 
            for _ in range(self.long()): n.v.append(self.obj())
            return n
        if c in '{}':
            n = self.reg(N('hash', []))
            for _ in range(self.long()):
                k = self.obj(); n.v.append((k, self.obj()))
            if c == '}': n.x = self.obj()
            return n
        if c == 'o':
            n = N('obj', cls=self.sym()); self.reg(n)
            for _ in range(self.long()):
                k = self.sym(); n.iv.append((k, self.obj()))
            return n
        if c == 'S':
            n = N('struct', cls=self.sym()); self.reg(n)
            for _ in range(self.long()):
                k = self.sym(); n.iv.append((k, self.obj()))
            return n
        if c == 'u':
            n = N('user', cls=self.sym()); n.v = self.bytes_(); return self.reg(n)
        if c == 'U':
            n = N('usrm', cls=self.sym()); self.reg(n); n.v = self.obj(); return n
        if c == '/':
            n = N('regexp', self.bytes_()); n.x = self.byte(); return self.reg(n)
        if c in 'cmM':
            n = N('class' if c == 'c' else 'module', self.bytes_()); n.x = c; return self.reg(n)
        raise ValueError('tipo Marshal nao suportado: %r na posicao %d' % (c, self.p))


class Writer:
    def __init__(self):
        self.o, self.syms, self.ids = bytearray(b'\x04\x08'), {}, {}

    def long(self, x):
        o = self.o
        if x == 0: o.append(0)
        elif 0 < x < 123: o.append(x + 5)
        elif -124 < x < 0: o.append((x - 5) & 0xff)
        else:
            buf = []
            for i in range(1, 5):
                buf.append(x & 0xff); x >>= 8
                if x == 0: o.append(i); break
                if x == -1: o.append((-i) & 0xff); break
            o.extend(buf)

    def bytes_(self, b):
        self.long(len(b)); self.o.extend(b)

    def sym(self, s):
        if s in self.syms:
            self.o.append(ord(';')); self.long(self.syms[s])
        else:
            self.syms[s] = len(self.syms)
            self.o.append(ord(':')); self.bytes_(s.encode('utf-8'))

    def obj(self, n):
        o, t = self.o, n.t
        if t == 'nil': o.append(ord('0')); return
        if t == 'true': o.append(ord('T')); return
        if t == 'false': o.append(ord('F')); return
        if t == 'fix': o.append(ord('i')); self.long(n.v); return
        if t == 'sym': self.sym(n.v); return
        if id(n) in self.ids:
            o.append(ord('@')); self.long(self.ids[id(n)]); return
        self.ids[id(n)] = len(self.ids)
        if n.iv and t in ('str', 'float', 'arr', 'hash', 'user', 'regexp', 'big', 'class', 'module'):
            o.append(ord('I'))
        for kind, name in n.pre:
            o.append(ord(kind)); self.sym(name)
        if t == 'str': o.append(ord('"')); self.bytes_(n.v)
        elif t == 'float': o.append(ord('f')); self.bytes_(n.v)
        elif t == 'big':
            o.append(ord('l')); o.extend(n.v[0]); self.long(n.v[1]); o.extend(n.v[2])
        elif t == 'arr':
            o.append(ord('[')); self.long(len(n.v))
            for c in n.v: self.obj(c)
        elif t == 'hash':
            o.append(ord('}' if n.x is not None else '{')); self.long(len(n.v))
            for k, v in n.v: self.obj(k); self.obj(v)
            if n.x is not None: self.obj(n.x)
        elif t in ('obj', 'struct'):
            o.append(ord('o' if t == 'obj' else 'S')); self.sym(n.cls); self.long(len(n.iv))
            for k, v in n.iv: self.sym(k); self.obj(v)
            return
        elif t == 'user': o.append(ord('u')); self.sym(n.cls); self.bytes_(n.v)
        elif t == 'usrm': o.append(ord('U')); self.sym(n.cls); self.obj(n.v); return
        elif t == 'regexp': o.append(ord('/')); self.bytes_(n.v); o.append(n.x)
        elif t in ('class', 'module'): o.append(ord(n.x)); self.bytes_(n.v)
        else: raise ValueError(t)
        if n.iv and t in ('str', 'float', 'arr', 'hash', 'user', 'regexp', 'big', 'class', 'module'):
            self.long(len(n.iv))
            for k, v in n.iv: self.sym(k); self.obj(v)


def marshal_load(b): 
    r = Reader(b); root = r.obj()
    if r.p != len(b): raise ValueError('bytes sobrando no arquivo')
    return root


def marshal_dump(n):
    w = Writer(); w.obj(n); return bytes(w.o)


# ----------------------------------------------------------------------------
# Adaptadores (JSON de MV/MZ  x  Marshal de XP/VX/Ace)
# ----------------------------------------------------------------------------
class JA:
    code = staticmethod(lambda c: c.get('code'))
    params = staticmethod(lambda c: c.get('parameters'))
    text = staticmethod(lambda p: p if isinstance(p, str) else None)

    @staticmethod
    def settext(params, i, t): params[i] = t

    @staticmethod
    def clone(proto, text=None):
        c = copy.deepcopy(proto)
        if text is not None: c['parameters'][0] = text
        return c


class MA:
    @staticmethod
    def code(c):
        v = c.ivar('@code'); return v.v if v is not None and v.t == 'fix' else None

    @staticmethod
    def params(c): return c.ivar('@parameters').v

    @staticmethod
    def text(p): return p.text() if p.t == 'str' else None

    @staticmethod
    def settext(params, i, t): params[i].set(t)

    @staticmethod
    def clone(proto, text=None):
        c = copy.deepcopy(proto)
        if text is not None: c.ivar('@parameters').v[0].set(text)
        return c


# ----------------------------------------------------------------------------
# Contexto: extrai (coleta) ou injeta (le traducoes)
# ----------------------------------------------------------------------------
class Ctx:
    def __init__(self, mode, entries=None, wrap=50, lines=4, protect=()):
        self.mode, self.wrap, self.lines = mode, wrap, lines
        self.protect = tuple(protect)   # ex.: ('Items', 'Weapons') -> nomes ficam intactos
        self.out, self.file, self.n = [], '', 0
        self.tr = {e['id']: e for e in (entries or [])}
        self.applied = self.skipped = 0
        self.warn = []

    def begin(self, file): self.file, self.n = file, 0

    def visit(self, kind, orig):
        eid = '%s|%d' % (self.file, self.n); self.n += 1
        if any(kind.startswith(b + '.') and kind.endswith(('name', 'nickname')) for b in self.protect):
            return None                 # contador avanca, entrada nao e traduzida
        if self.mode == 'extract':
            self.out.append({'id': eid, 'type': kind, 'orig': orig, 'tr': ''}); return None
        e = self.tr.get(eid)
        if not e or not e.get('tr') or e['tr'] == orig: return None
        if e['orig'] != orig:
            self.warn.append('%s: texto original mudou, ignorado' % eid); self.skipped += 1; return None
        if codes_of(orig) != codes_of(e['tr']):
            self.warn.append('%s: codigos de controle diferem, ignorado: %r' % (eid, e['tr'][:60]))
            self.skipped += 1; return None
        self.applied += 1
        return e['tr']


def process_commands(cmds, A, ctx):
    out, i = [], 0
    while i < len(cmds):
        c = cmds[i]; code = A.code(c)
        if code in (101, 105):
            body = 401 if code == 101 else 405
            params = A.params(c)
            if code == 101 and len(params) > 4 and A.text(params[4]) and JP.search(A.text(params[4])):
                r = ctx.visit('speaker', A.text(params[4]))
                if r: A.settext(params, 4, r)
            j, run = i + 1, []
            while j < len(cmds) and A.code(cmds[j]) == body:
                run.append(cmds[j]); j += 1
            lines = [A.text(A.params(r)[0]) or '' for r in run]
            tr = ctx.visit('message' if code == 101 else 'scroll', '\n'.join(lines)) \
                if run and JP.search('\n'.join(lines)) else None
            if tr:
                groups = layout(tr, ctx.wrap, ctx.lines if code == 101 else None)
                for g, chunk in enumerate(groups):
                    out.append(c if g == 0 else A.clone(c))
                    for ln in chunk: out.append(A.clone(run[0], ln))
            else:
                out.append(c); out.extend(run)
            i = j; continue
        if code == 102:
            params = A.params(c)
            arr = params[0].v if hasattr(params[0], 'v') and hasattr(params[0], 't') else params[0]
            for k, ch in enumerate(arr):
                s = A.text(ch)
                if s and JP.search(s):
                    r = ctx.visit('choice', s)
                    if r: A.settext(arr, k, r)
        elif code in (320, 324):
            params = A.params(c)
            if len(params) > 1 and A.text(params[1]) and JP.search(A.text(params[1])):
                r = ctx.visit('actor_name', A.text(params[1]))
                if r: A.settext(params, 1, r)
        out.append(c); i += 1
    cmds[:] = out


# --- JSON (MV/MZ) -----------------------------------------------------------
DB_FIELDS = ('name', 'description', 'nickname', 'profile', 'message1', 'message2', 'message3', 'message4')
JSON_DB = ('Actors', 'Armors', 'Classes', 'Enemies', 'Items', 'Skills', 'States', 'Weapons')


def jstr(ctx, d, key, kind):
    s = d.get(key)
    if isinstance(s, str) and JP.search(s):
        r = ctx.visit(kind, s)
        if r: d[key] = r


def jlist(ctx, lst, kind):
    for i, s in enumerate(lst):
        if isinstance(s, str) and JP.search(s):
            r = ctx.visit(kind, s)
            if r: lst[i] = r


def json_file(name, data, ctx):
    base = name[:-5]
    if base in JSON_DB:
        for d in data:
            if d:
                for f in DB_FIELDS: jstr(ctx, d, f, base + '.' + f)
    elif base == 'System':
        for k in ('gameTitle', 'currencyUnit'): jstr(ctx, data, k, 'System.' + k)
        for k in ('armorTypes', 'weaponTypes', 'skillTypes', 'equipTypes', 'elements'):
            if k in data: jlist(ctx, data[k], 'System.' + k)
        t = data.get('terms', {})
        for k, v in t.items():
            if isinstance(v, list): jlist(ctx, v, 'terms.' + k)
            elif isinstance(v, dict):
                for kk in v:
                    jstr(ctx, v, kk, 'terms.%s.%s' % (k, kk))
    elif base in ('CommonEvents', 'Troops'):
        for d in data:
            if d:
                if 'list' in d: process_commands(d['list'], JA, ctx)
                for p in d.get('pages', []): process_commands(p['list'], JA, ctx)
    elif re.fullmatch(r'Map\d+', base):
        jstr(ctx, data, 'displayName', 'Map.displayName')
        for e in data.get('events', []):
            if e:
                for p in e.get('pages', []): process_commands(p['list'], JA, ctx)


# --- Marshal (XP/VX/Ace) -----------------------------------------------------
M_DB = {'RPG::Actor', 'RPG::Class', 'RPG::Skill', 'RPG::Item', 'RPG::Weapon', 'RPG::Armor',
        'RPG::Enemy', 'RPG::State'}
M_FIELDS = {'@' + f for f in DB_FIELDS}


def mstr(ctx, n, kind):
    if n.t == 'str':
        s = n.text()
        if JP.search(s):
            r = ctx.visit(kind, s)
            if r: n.set(r)


def marshal_walk(n, ctx):
    if n.t == 'arr':
        if n.v and n.v[0].t == 'obj' and n.v[0].cls == 'RPG::EventCommand':
            process_commands(n.v, MA, ctx); return
        for c in n.v: marshal_walk(c, ctx)
    elif n.t == 'hash':
        for _, v in n.v: marshal_walk(v, ctx)
    elif n.t == 'obj':
        if n.cls in M_DB:
            for k, v in n.iv:
                if k in M_FIELDS: mstr(ctx, v, n.cls + '.' + k)
        elif n.cls == 'RPG::System':
            for k, v in n.iv:
                if k in ('@game_title', '@currency_unit'): mstr(ctx, v, 'System.' + k)
                elif k in ('@elements', '@skill_types', '@weapon_types', '@armor_types') and v.t == 'arr':
                    for s in v.v: mstr(ctx, s, 'System.' + k)
        elif n.cls in ('RPG::System::Terms', 'RPG::System::Words'):
            for k, v in n.iv:
                if v.t == 'str': mstr(ctx, v, 'terms.' + k)
                elif v.t == 'arr':
                    for s in v.v: mstr(ctx, s, 'terms.' + k)
            return
        elif n.cls == 'RPG::Map':
            for k, v in n.iv:
                if k == '@display_name': mstr(ctx, v, 'Map.display_name')
        for k, v in n.iv:
            if v.t in ('arr', 'hash', 'obj'): marshal_walk(v, ctx)


# ----------------------------------------------------------------------------
# Localizacao dos arquivos e deteccao da engine
# ----------------------------------------------------------------------------
def find_data(root):
    if os.path.isfile(root): root = os.path.dirname(root)
    cands = [root] + [os.path.join(root, a) for a in ('data', 'Data', 'www/data', 'www/Data')]
    for c in cands:
        if os.path.isdir(c):
            fs = os.listdir(c)
            if any(f.lower().endswith(('.json', '.rxdata', '.rvdata', '.rvdata2', '.lmu', '.ldb')) for f in fs):
                return c
    return None


def detect(root):
    d = find_data(root)
    top = os.listdir(root) if os.path.isdir(root) else []
    low = [f.lower() for f in top]
    if any(f.endswith('.wolf') for f in low) or 'data.wolf' in low or os.path.isdir(os.path.join(root, 'Data')) and \
            any(f.lower().endswith(('.wolf', '.mps', '.dat')) for f in os.listdir(os.path.join(root, 'Data'))):
        return 'wolf', d
    if d is None: return None, None
    fs = [f.lower() for f in os.listdir(d)]
    if any(f.endswith('.rvdata2') for f in fs): return 'ace', d
    if any(f.endswith('.rvdata') for f in fs): return 'vx', d
    if any(f.endswith('.rxdata') for f in fs): return 'xp', d
    if any(f.endswith(('.lmu', '.ldb')) for f in fs) or any(f.endswith('.lmu') for f in low): return '2k', d
    if any(f.endswith('.json') for f in fs): return 'mvmz', d
    return None, d


EXTERNAL = {
    '2k': "RPG Maker 2000/2003 usa .ldb/.lmu (formato LCF, binario). Use o liblcf/EasyRPG (lcf2xml) para "
          "converter para XML, traduza os textos e reconverta; ou um tradutor dedicado que suporte 2000/2003.",
    'wolf': "Wolf RPG Editor usa .wolf/.dat/.mps (muitas vezes criptografado). Use ferramentas como WolfDec "
            "(descriptografar) e WolfTL / Wolf Trans (extrair e reinserir). Nao incluidas aqui por nao serem "
            "formato que eu possa validar sem arquivos reais.",
}


def iter_files(data_dir, engine):
    for f in sorted(os.listdir(data_dir)):
        p = os.path.join(data_dir, f)
        if not os.path.isfile(p): continue
        if engine == 'mvmz' and f.endswith('.json'): yield f
        elif engine == 'xp' and f.endswith('.rxdata'): yield f
        elif engine == 'vx' and f.endswith('.rvdata'): yield f
        elif engine == 'ace' and f.endswith('.rvdata2'): yield f


def run(mode, root, entries=None, outdir=None, wrap=50, lines=4, protect=()):
    eng, d = detect(root)
    if eng in EXTERNAL:
        sys.exit('Engine detectada: %s\n%s' % (eng, EXTERNAL[eng]))
    if eng is None:
        sys.exit('Nao encontrei arquivos de dados RPG Maker em %s' % root)
    ctx = Ctx(mode, entries, wrap, lines, protect)
    if outdir:
        os.makedirs(outdir, exist_ok=True)
    for f in iter_files(d, eng):
        src = os.path.join(d, f)
        ctx.begin(f)
        if eng == 'mvmz':
            with open(src, encoding='utf-8') as fh: data = json.load(fh)
            json_file(f, data, ctx)
            if outdir:
                with open(os.path.join(outdir, f), 'w', encoding='utf-8') as fh:
                    json.dump(data, fh, ensure_ascii=False, separators=(',', ':'))
        else:
            with open(src, 'rb') as fh: raw = fh.read()
            try:
                tree = marshal_load(raw)
            except Exception as ex:
                print('  [aviso] %s nao lido (%s), ignorado' % (f, ex)); continue
            marshal_walk(tree, ctx)
            if outdir:
                with open(os.path.join(outdir, f), 'wb') as fh: fh.write(marshal_dump(tree))
    return eng, ctx


# ----------------------------------------------------------------------------
# Traducao automatica opcional (endpoint web nao oficial do Google; precisa de internet)
# ----------------------------------------------------------------------------
def mt_request(text, src, tgt):
    url = 'https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=%s&tl=%s&q=%s' % (
        src, tgt, urllib.parse.quote(text))
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'}), timeout=20) as r:
                return ''.join(seg[0] for seg in json.loads(r.read().decode('utf-8'))[0] if seg[0])
        except Exception:
            time.sleep(1.5 * (attempt + 1))
    return None


def mt_entry(orig, src, tgt):
    joiner = '' if src in ('ja', 'zh', 'zh-CN', 'zh-TW') else ' '
    text = joiner.join(l.strip() for l in orig.split('\n'))
    m = LEAD.match(text); lead = m.group(0) if m else ''
    text = text[len(lead):]
    codes = []
    def mask(mo):
        codes.append(mo.group(0)); return ' {%d} ' % (len(codes) - 1)
    masked = CTRL.sub(mask, text)
    res = mt_request(masked, src, tgt) if masked.strip() else masked
    if res is None: return None
    res = re.sub(r'\{\s*(\d+)\s*\}', lambda mo: '{%s}' % mo.group(1), res)
    for k in range(len(codes)):
        if res.count('{%d}' % k) != 1: return None
        res = res.replace('{%d}' % k, codes[k])
    res = re.sub(r'\s{2,}', ' ', res).strip()
    return lead + res


def cmd_translate(a):
    with open(a.strings, encoding='utf-8') as fh: doc = json.load(fh)
    ents, cache, done, fail = doc['entries'], {}, 0, 0
    for k, e in enumerate(ents):
        if e['tr'] or not JP.search(e['orig']): continue
        if e['orig'] in cache: e['tr'] = cache[e['orig']]; continue
        tr = mt_entry(e['orig'], a.src, a.tgt)
        if tr is None: fail += 1; continue
        e['tr'] = cache[e['orig']] = tr; done += 1
        time.sleep(0.15)
        if done % 50 == 0:
            with open(a.strings, 'w', encoding='utf-8') as fh: json.dump(doc, fh, ensure_ascii=False, indent=1)
            print('  %d/%d' % (k + 1, len(ents)), flush=True)
    with open(a.strings, 'w', encoding='utf-8') as fh: json.dump(doc, fh, ensure_ascii=False, indent=1)
    print('Traduzidas: %d | falhas (rode de novo): %d' % (done, fail))


# ----------------------------------------------------------------------------
# Verificador de plugins (MV/MZ): avisa o que depende de texto em japones
# ----------------------------------------------------------------------------
JPC = r'[\u3040-\u30ff\u4e00-\u9fff]'
DB_TYPES = ('Items', 'Weapons', 'Armors', 'Skills', 'Actors', 'Classes', 'Enemies', 'States')


def _strip_comments(code):
    code = re.sub(r'/\*.*?\*/', '', code, flags=re.S)
    return re.sub(r'(?m)^\s*//.*$', '', code)


def find_js_dir(root):
    for c in ('js', 'www/js', ''):
        d = os.path.join(root, c)
        if os.path.isdir(os.path.join(d, 'plugins')): return d
    return None


def check_plugins(root):
    js = find_js_dir(root)
    if not js: sys.exit('Nao achei a pasta js/plugins em %s' % root)
    risks = collections.defaultdict(lambda: collections.defaultdict(list))
    literals = collections.defaultdict(set)
    for f in sorted(os.listdir(os.path.join(js, 'plugins'))):
        if not f.endswith('.js'): continue
        code = _strip_comments(open(os.path.join(js, 'plugins', f), encoding='utf-8', errors='ignore').read())
        if len(code) > 20000 and code.count('\n') < 20: continue          # lib minificada
        for ln in code.split('\n'):
            t = ln.strip()[:120]
            if not t: continue
            for ty in DB_TYPES:
                if re.search(r'\$data%s\b' % ty, t) and re.search(r'\.name\s*(===?|!==?)\s*(?!["\']{2})|\.name\.(match|includes|indexOf)', t):
                    risks[f]['ALTO:db:' + ty].append(t)
            if re.search(r'\.name\(\)\s*===?|actor\([^)]*\)\.name\(\)\.(match|includes)', t):
                risks[f]['ALTO:actor_name'].append(t)
            if re.search(r'displayName\s*(===?|\.match|\.includes)', t):
                risks[f]['ALTO:map_name'].append(t)
            if re.search(r'event\(\)\.name|\be\.name\s*===|\.name\s*===\s*eventName|events\.find', t):
                risks[f]['OK:event_name'].append(t)
            if re.search(r'findCommentCommand|code\s*===?\s*(108|408)', t) and re.search(JPC, t):
                risks[f]['OK:comment_tag'].append(t)
            if re.search(r'(findMetaValue|meta\[|\.meta\.)', t) and re.search(JPC, t):
                risks[f]['OK:note_tag'].append(t)
            if re.search(r'registerCommand|comName|commandName', t) and re.search(JPC, t):
                risks[f]['OK:plugin_command'].append(t)
            m = re.findall(r'(?:===?|case|includes\()\s*\(?[\'"]([^\'"]*%s[^\'"]*)[\'"]' % JPC, t)
            for lit in m:
                literals[f].add(lit); risks[f]['OK:valor_jp_comparado'].append(t)
            if re.search(r'\beval\(|new Function\(', t):
                risks[f]['OK:eval_em_parametro'].append(t)
    return js, risks, literals


def plugins_js_report(js, literals):
    p = os.path.join(js, 'plugins.js')
    if not os.path.isfile(p): return []
    s = open(p, encoding='utf-8').read()
    m = re.search(r'\[\s*\{.*\}\s*\]', s, re.S)
    if not m: return []
    out = []
    for pl in json.loads(m.group(0)):
        prot = cand = 0
        def scan(v):
            nonlocal prot, cand
            if isinstance(v, str):
                try: scan(json.loads(v)); return
                except Exception: pass
                if re.search(JPC, v):
                    if v in literals.get(pl['name'] + '.js', ()): prot += 1
                    else: cand += 1
            elif isinstance(v, list): [scan(x) for x in v]
            elif isinstance(v, dict): [scan(x) for x in v.values()]
        scan(pl.get('parameters', {}))
        if prot or cand: out.append((pl['name'], pl['status'], cand, prot))
    return out


def cmd_check(a):
    js, risks, literals = check_plugins(a.game)
    high = {f: {k: v for k, v in d.items() if k.startswith('ALTO')} for f, d in risks.items()}
    high = {f: d for f, d in high.items() if d}
    print('== RISCO ALTO (quebra se o app traduzir esse dado) ==')
    protect = set()
    if not high: print('  nenhum')
    for f, d in high.items():
        for k, v in d.items():
            kind = k.split(':', 1)[1]
            if kind.startswith('db:'): protect.add(kind[3:])
            print('  %s -> %s\n      ex.: %s' % (f, kind, v[0]))
    if protect: print('\n  => use: --protect %s (nomes desses dados ficam em japones)' % ','.join(sorted(protect)))
    print('\n== OK: o app NAO altera estes dados, entao nao ha risco ==')
    names = {'event_name': 'nome de evento', 'comment_tag': 'comentario (108/408)', 'note_tag': 'tag de nota',
             'plugin_command': 'comando/argumento de plugin', 'valor_jp_comparado': 'valor japones comparado',
             'eval_em_parametro': 'codigo JS em parametro'}
    agg = collections.defaultdict(list)
    for f, d in risks.items():
        for k in d:
            if k.startswith('OK:'): agg[k[3:]].append(f[:-3])
    for k, fl in agg.items(): print('  %-28s %s' % (names.get(k, k), ', '.join(sorted(fl)[:8]) + (' ...' if len(fl) > 8 else '')))
    print('\n== Textos de UI nos parametros do plugins.js (o app nao traduz; traduza manualmente se quiser) ==')
    print('  (candidato = texto livre; protegido = valor que o codigo compara, NAO traduzir)')
    for name, st, cand, prot in plugins_js_report(js, literals):
        print('  %-34s %s candidatos=%d protegidos=%d' % (name, 'ativo ' if st else 'inativo', cand, prot))
    print('\nObs.: nomes de arquivos de plugin nao podem ser renomeados (alguns usam document.currentScript).')


# ----------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest='cmd', required=True)
    p = sp.add_parser('detect'); p.add_argument('game')
    p = sp.add_parser('check-plugins'); p.add_argument('game')
    p = sp.add_parser('extract'); p.add_argument('game'); p.add_argument('-o', '--out', default='strings.json')
    p.add_argument('--protect', default='', help='ex.: Items,Weapons -> nao traduz nomes desses dados')
    p = sp.add_parser('translate'); p.add_argument('strings')
    p.add_argument('--src', default='ja'); p.add_argument('--tgt', default='en')
    p = sp.add_parser('inject'); p.add_argument('game'); p.add_argument('strings')
    p.add_argument('-o', '--out', required=True, help='pasta de saida para a data traduzida')
    p.add_argument('--wrap', type=int, default=50, help='largura maxima por linha (0 = nao quebrar)')
    p.add_argument('--lines', type=int, default=4, help='linhas por janela de mensagem')
    p.add_argument('--protect', default='', help='igual ao extract; use o mesmo valor nos dois')
    a = ap.parse_args()

    if a.cmd == 'detect':
        eng, d = detect(a.game); print('engine:', eng, '| data:', d)
        if eng in EXTERNAL: print(EXTERNAL[eng])
    elif a.cmd == 'check-plugins':
        cmd_check(a)
    elif a.cmd == 'extract':
        eng, ctx = run('extract', a.game, protect=[x for x in a.protect.split(',') if x])
        with open(a.out, 'w', encoding='utf-8') as fh:
            json.dump({'engine': eng, 'entries': ctx.out}, fh, ensure_ascii=False, indent=1)
        print('%s: %d textos extraidos -> %s' % (eng, len(ctx.out), a.out))
    elif a.cmd == 'translate':
        cmd_translate(a)
    elif a.cmd == 'inject':
        with open(a.strings, encoding='utf-8') as fh: doc = json.load(fh)
        eng, ctx = run('inject', a.game, doc['entries'], a.out, a.wrap, a.lines, [x for x in a.protect.split(',') if x])
        print('Aplicadas: %d | ignoradas por seguranca: %d' % (ctx.applied, ctx.skipped))
        for w in ctx.warn[:30]: print('  -', w)
        print('Copie os arquivos de "%s" por cima da pasta data de uma COPIA do jogo.' % a.out)


if __name__ == '__main__':
    main()
