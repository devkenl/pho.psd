#!/usr/bin/env python3
"""
Generates Kotlin *stub* sources from AndroidX Metalava API signature files
(`api/current.txt`, signature format 5.0).

Purpose: type-check the pho.psd app module in environments that cannot reach Google's
Maven repository (no AAR downloads). The stubs carry the public Kotlin API surface of
Compose/AndroidX with `TODO()` bodies; they are never shipped or executed.

Limitations (deliberate): no Compose compiler plugin semantics, sealed/value modifiers
dropped, receiver-ness of function-type parameters inferred heuristically, and any stub
declaration kotlinc rejects is pruned by compile_check.sh (error-driven). A clean app
compile against these stubs is strong evidence, not proof; the real Gradle build is the
source of truth.

Usage: api2kt.py OUT_DIR api1.txt [api2.txt ...]
"""
import os
import re
import sys
from collections import defaultdict

KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
}

PRIMS = {
    "int": "Int", "long": "Long", "float": "Float", "double": "Double", "boolean": "Boolean",
    "byte": "Byte", "char": "Char", "short": "Short", "void": "Unit",
}
PRIM_ARRAYS = {
    "int": "IntArray", "long": "LongArray", "float": "FloatArray", "double": "DoubleArray",
    "boolean": "BooleanArray", "byte": "ByteArray", "char": "CharArray", "short": "ShortArray",
}
JAVA_MAP = {
    "String": "String", "java.lang.String": "String", "Object": "Any", "java.lang.Object": "Any",
    "java.lang.Integer": "Int", "java.lang.Long": "Long", "java.lang.Float": "Float",
    "java.lang.Double": "Double", "java.lang.Boolean": "Boolean", "java.lang.Byte": "Byte",
    "java.lang.Character": "Char", "java.lang.Short": "Short", "java.lang.CharSequence": "CharSequence",
    "java.lang.Number": "Number", "java.lang.Comparable": "Comparable", "java.lang.Iterable": "Iterable",
    "java.util.List": "List", "java.util.Map": "Map", "java.util.Set": "Set",
    "java.util.Collection": "Collection", "java.lang.Throwable": "Throwable", "java.lang.Enum": "Enum",
    "java.lang.annotation.Annotation": "Annotation", "java.lang.Void": "Unit",
    "java.util.Iterator": "Iterator", "java.util.Map.Entry": "Map.Entry",
}
RECEIVER_TYPES = {
    "androidx.compose.ui.unit.Density", "androidx.compose.ui.semantics.SemanticsPropertyReceiver",
    "kotlinx.coroutines.CoroutineScope", "androidx.compose.ui.graphics.drawscope.DrawScope",
    "androidx.compose.ui.graphics.GraphicsLayerScope",
}


def strip_annotations(s):
    """Removes @Annotations (with balanced parenthesised args) from a declaration."""
    out = []
    i = 0
    while i < len(s):
        if s[i] == "@" and (i == 0 or s[i - 1] in " (<,") and not s.startswith("@interface", i):
            j = i + 1
            while j < len(s) and (s[j].isalnum() or s[j] in "._$"):
                j += 1
            if j < len(s) and s[j] == "(":
                depth = 0
                while j < len(s):
                    if s[j] == "(":
                        depth += 1
                    elif s[j] == ")":
                        depth -= 1
                        if depth == 0:
                            j += 1
                            break
                    j += 1
            i = j
            while i < len(s) and s[i] == " ":
                i += 1
            continue
        out.append(s[i])
        i += 1
    return "".join(out)


def split_top(s, sep=","):
    parts, depth, cur = [], 0, []
    for ch in s:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == sep and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def esc(name):
    return f"`{name}`" if name in KEYWORDS or "$" in name or "-" in name else name


class Ctx:
    """Type-name resolution context: type parameters in scope stay unqualified."""

    def __init__(self, tparams=()):
        self.tparams = set(tparams)


def kt_type(t, ctx, *, for_receiver=False):
    t = t.strip()
    if re.match(r"^kotlin\.reflect\.KProperty\d?<", t.replace("? extends ", "")):
        return "kotlin.reflect.KProperty<*>"
    if not t:
        return "Any?"
    t = t.replace("!", "")
    nullable = False
    if t.endswith("?"):
        nullable = True
        t = t[:-1].strip()
    vararg = False
    if t.endswith("..."):
        vararg = True
        t = t[:-3]
    if t == "?":
        return "*"
    if t.startswith("? super "):
        return "in " + kt_type(t[8:], ctx)
    if t.startswith("? extends "):
        return "out " + kt_type(t[10:], ctx)
    if t.endswith("[]"):
        base = t[:-2]
        if base in PRIM_ARRAYS:
            r = PRIM_ARRAYS[base]
        else:
            r = f"Array<{kt_type(base, ctx)}>"
        return r + ("?" if nullable else "")
    m = re.match(r"^([\w.$]+)(?:<(.*)>)?$", t)
    if not m:
        return "Any?"
    raw, args = m.group(1), m.group(2)
    if raw in PRIMS:
        r = PRIMS[raw]
    elif raw.startswith("kotlin.jvm.functions.Function"):
        r = function_type(split_top(args) if args else [], ctx)
        return f"({r})?" if nullable else r
    else:
        r = JAVA_MAP.get(raw, raw.replace("$", "."))
        if args:
            r += "<" + ", ".join(kt_type(a, ctx) for a in split_top(args)) + ">"
    if vararg:
        r = r  # handled by caller
    return r + ("?" if nullable else "")


def function_type(targs, ctx):
    targs = [a.strip() for a in targs]
    ret = targs[-1] if targs else "kotlin.Unit"
    params = targs[:-1]
    suspend = False
    if params and re.match(r"^(\? super )?kotlin\.coroutines\.Continuation<", params[-1].replace("!", "")):
        cont = params[-1].replace("!", "")
        inner = cont[cont.index("<") + 1: cont.rindex(">")]
        ret = inner
        params = params[:-1]
        suspend = True
    ret_kt = kt_type(strip_variance(ret), ctx)
    receiver = None
    if params:
        first = strip_variance(params[0]).replace("!", "")
        fname = first.split("<")[0]
        if fname.endswith("Scope") or fname in RECEIVER_TYPES or fname.endswith("ScopeMarker"):
            receiver = kt_type(first, ctx)
            params = params[1:]
    plist = ", ".join(kt_type(strip_variance(p), ctx) for p in params)
    ft = f"({plist}) -> {ret_kt}"
    if receiver:
        ft = f"{receiver}.{ft}"
    if suspend:
        ft = "suspend " + ft
    return ft


def strip_variance(t):
    t = t.strip()
    for p in ("? super ", "? extends "):
        if t.startswith(p):
            return t[len(p):]
    if t == "?":
        return "Any?"
    return t


def parse_tparams(s):
    """'<T extends A, R>' -> (['T', 'R'], 'T : A, R')"""
    if not s:
        return [], ""
    inner = s.strip()[1:-1]
    names, decl = [], []
    for p in split_top(inner):
        p = p.replace("reified ", "")
        if " extends " in p:
            n, b = p.split(" extends ", 1)
            b = b.split(" & ")[0]
            names.append(n.strip())
            decl.append((n.strip(), b.strip()))
        else:
            names.append(p.strip())
            decl.append((p.strip(), None))
    return names, decl


def render_tparams(decl, ctx):
    if not decl:
        return ""
    return "<" + ", ".join(f"{n} : {kt_type(b, ctx)}" if b else n for n, b in decl) + ">"


class ClassInfo:
    def __init__(self, pkg, header):
        self.pkg = pkg
        self.header = header
        self.members = []
        h = strip_annotations(header).rstrip("{").strip()
        self.visibility = "public"
        head_tokens = re.split(r"\b(?:class|interface|enum|@interface)\b", h)[0].split()
        for v in ("public", "protected", "internal", "private"):
            if v in head_tokens:
                self.visibility = v
        self.is_static = " static " in f" {h} "
        self.is_abstract = " abstract " in f" {h} "
        self.is_final = " final " in f" {h} "
        self.is_fun = " fun interface " in f" {h} "
        if re.search(r"\b@interface\b|\bpublic @interface\b|\s@interface\s", " " + h + " ") or "@interface" in h:
            self.kind = "annotation"
        elif re.search(r"\benum\b", h):
            self.kind = "enum"
        elif re.search(r"\binterface\b", h):
            self.kind = "interface"
        else:
            self.kind = "class"
        m = re.search(r"\b(?:class|interface|enum|@interface)\s+(.*)$", h)
        rest = m.group(1) if m else ""
        # name with generics
        depth = 0
        i = 0
        while i < len(rest):
            ch = rest[i]
            if ch == "<":
                depth += 1
            elif ch == ">":
                depth -= 1
            elif ch == " " and depth == 0:
                break
            i += 1
        full = rest[:i]
        self.tail = rest[i:].strip()
        if "<" in full:
            self.dotted = full[: full.index("<")]
            self.tparams_src = full[full.index("<"):]
        else:
            self.dotted = full
            self.tparams_src = ""
        self.simple = self.dotted.split(".")[-1]
        ext = re.search(r"\bextends\s+(.+?)(?:\s+implements\s+|$)", self.tail)
        impl = re.search(r"\bimplements\s+(.+)$", self.tail)
        self.extends = split_top(ext.group(1), ",") if ext else []
        if self.kind == "interface":
            # interfaces list their super-interfaces under "extends"
            self.extends = [x for e in self.extends for x in e.split(" ")] if self.extends else []
            self.implements = []
        else:
            self.implements = impl.group(1).split(" ") if impl else []
            self.implements = [x for x in self.implements if x]
        self.children = []

    @property
    def fq(self):
        return f"{self.pkg}.{self.dotted}"


MEMBER_RE = re.compile(r"^(ctor|method|property|field|enum_constant)\s+(.*);$")


def parse_files(paths):
    classes = {}
    for path in paths:
        pkg = None
        cur = None
        for raw in open(path, encoding="utf-8"):
            line = raw.rstrip("\n")
            s = line.strip()
            if not s or s.startswith("//"):
                continue
            if s.startswith("package ") and s.endswith("{"):
                pkg = strip_annotations(s[len("package "):-1]).strip().split()[-1]
                continue
            if line.startswith("  ") and not line.startswith("    ") and s.endswith("{"):
                cur = ClassInfo(pkg, s)
                if "@BytecodeOnly" in s or cur.visibility in ("internal", "private"):
                    cur = None
                    continue
                classes.setdefault(cur.fq, cur)
                cur = classes[cur.fq]
                continue
            if s == "}":
                cur = None
                continue
            if cur is not None:
                m = MEMBER_RE.match(s)
                if m:
                    cur.members.append((m.group(1), m.group(2)))
    return classes


def member_skipped(body):
    toks = strip_annotations(body).split("(")[0].split()
    return ("@BytecodeOnly" in body or "@InaccessibleFromKotlin" in body
            or "internal" in toks or "private" in toks)


def parse_params(src, ctx, allow_receiver):
    params = split_top(src) if src.strip() else []
    receiver = None
    out = []
    suspend_ret = None
    for idx, p in enumerate(params):
        p = strip_annotations(p).strip()
        optional = False
        if p.startswith("optional "):
            optional = True
            p = p[len("optional "):]
        # name is the last token at depth 0 (if any)
        tokens = split_top(p, " ")
        if re.match(r"^(\? super )?kotlin\.coroutines\.Continuation<", p.replace("!", "")) and idx == len(params) - 1 and len(tokens) == 1:
            inner = p.replace("!", "")
            suspend_ret = inner[inner.index("<") + 1: inner.rindex(">")]
            continue
        if len(tokens) >= 2 and re.match(r"^[A-Za-z_$][\w$]*$", tokens[-1]) and not tokens[-1].startswith("?"):
            name = tokens[-1]
            typ = " ".join(tokens[:-1])
        else:
            name = None
            typ = p
        if name is None and idx == 0 and allow_receiver and receiver is None:
            receiver = typ
            continue
        if name is None:
            name = f"p{idx}"
        vararg = typ.endswith("...")
        if vararg:
            typ = typ[:-3]
        kt = kt_type(typ, ctx)
        s = f"{'vararg ' if vararg else ''}{esc(name)}: {kt}"
        if optional:
            s += " = TODO()"
        out.append(s)
    return receiver, out, suspend_ret


def gen_method(body, ctx_names, *, in_interface, top_level, owner_abstract):
    b = strip_annotations(body)
    if "(" not in b:
        return None
    head, rest = b.split("(", 1)
    params_src = rest[: rest.rindex(")")]
    toks = split_top(head.strip(), " ")
    if len(toks) < 2:
        return None
    name = toks[-1]
    ret_src = toks[-2]
    pre = toks[:-2]
    tp_src = next((t for t in pre if t.startswith("<")), "")
    mods = set(t for t in pre if not t.startswith("<"))
    names, decl = parse_tparams(tp_src)
    ctx = Ctx(list(ctx_names) + names)
    is_static = "static" in mods
    receiver, params, suspend_ret = parse_params(params_src, ctx, allow_receiver=True)
    suspend = "suspend" in mods or suspend_ret is not None
    if suspend_ret is not None:
        ret_src = suspend_ret
    ret_src = strip_variance(ret_src)
    ret = kt_type(ret_src, ctx)
    kmods = []
    if "operator" in mods:
        kmods.append("operator")
    if "infix" in mods:
        kmods.append("infix")
    if suspend:
        kmods.append("suspend")
    reified = "reified" in tp_src
    if reified:
        kmods.append("inline")
        # nullable/defaulted lambdas cannot be inlined
        params = [("noinline " + p_) if ("->" in p_ and not p_.startswith("vararg")) else p_ for p_ in params]
    if "protected" in mods:
        kmods.insert(0, "protected")
    abstract = False
    if in_interface and "default" not in mods and not is_static:
        abstract = True
    if "abstract" in mods and owner_abstract:
        abstract = True
    if not top_level and not in_interface and not abstract and "final" not in mods:
        kmods.insert(0, "open")
    if abstract and not in_interface:
        kmods.insert(0, "abstract")
    if name in ("equals", "hashCode", "toString") and not top_level:
        kmods = [k for k in kmods if k != "open"]
        kmods.insert(0, "override")
    tps = render_tparams([(n, b) for n, b in decl], ctx)
    if reified:
        tps = tps.replace("<", "<reified ", 1) if tps else tps
    recv = (kt_type(receiver, ctx) + ".") if receiver else ""
    if recv.startswith("("):
        pass
    elif recv and ("->" in recv):
        recv = f"({recv[:-1]})."
    sig = f"{' '.join(kmods)} fun {tps + ' ' if tps else ''}{recv}{esc(name)}({', '.join(params)}): {ret}".strip()
    if abstract:
        return sig
    return sig + " = TODO()"


KNOWN_MODS = {"public", "protected", "static", "final", "abstract", "default", "inline", "operator",
              "infix", "suspend", "synchronized", "native", "open", "external", "tailrec", "const",
              "lateinit", "transient", "volatile", "strictfp"}


def gen_property(body, ctx_names, *, in_interface, top_level, setters, owner_abstract):
    b = strip_annotations(body)
    toks = split_top(b, " ")
    mods = set(t for t in toks if t in KNOWN_MODS)
    rest = [t for t in toks if t not in KNOWN_MODS]
    if len(rest) < 2:
        return None
    typ = " ".join(rest[:-1])
    full_name = rest[-1]
    receiver = None
    if "." in full_name:
        receiver, name = full_name.rsplit(".", 1)
    else:
        name = full_name
    ctx = Ctx(ctx_names)
    kt = kt_type(typ, ctx)
    is_var = name in setters
    kw = "var" if is_var else "val"
    kmods = []
    abstract = (in_interface and "default" not in mods and receiver is None) or ("abstract" in mods and owner_abstract)
    if not top_level and not in_interface and not abstract and "final" not in mods:
        kmods.append("open")
    if abstract and not in_interface:
        kmods.append("abstract")
    recv = ""
    if receiver:
        recv = kt_type(receiver, ctx) + "."
    tp = ""
    gen = re.findall(r"\b([A-Z])\b", receiver or "")
    if receiver and gen:
        tp = "<" + ", ".join(sorted(set(gen))) + "> "
    head = f"{' '.join(kmods)} {kw} {tp}{recv}{esc(name)}: {kt}".strip()
    if abstract:
        return head
    if is_var:
        return head + " get() = TODO(); set(value) {}"
    return head + " get() = TODO()"


def gen_field(body, ctx_names):
    b = strip_annotations(body)
    b = b.split(" = ")[0]
    toks = split_top(b, " ")
    rest = [t for t in toks if t not in KNOWN_MODS]
    if len(rest) < 2:
        return None
    return f"val {esc(rest[-1])}: {kt_type(' '.join(rest[:-1]), Ctx(ctx_names))} get() = TODO()"


def setter_names(members):
    names = set()
    for kind, body in members:
        if kind == "method":
            m = re.search(r"\bset([A-Z]\w*)\(", body)
            if m:
                n = m.group(1)
                names.add(n[0].lower() + n[1:])
                names.add("is" + n)
    return names


MEMBER_INDEX = {}


def member_keys(ci):
    keys = set()
    for kind, body in ci.members:
        if member_skipped(body):
            continue
        b = strip_annotations(body)
        if " static " in f" {b} ":
            continue
        if kind == "method" and "(" in b:
            name = b.split("(")[0].split()[-1]
            inner = b[b.index("(") + 1: b.rindex(")")]
            n = len([x for x in split_top(inner) if "kotlin.coroutines.Continuation" not in x]) if inner.strip() else 0
            keys.add(("m", name, n))
        elif kind == "property":
            keys.add(("p", split_top(b, " ")[-1].split(".")[-1]))
    return keys


def ancestors(ci, all_classes, seen=None):
    seen = seen if seen is not None else set()
    sups = (ci.extends if ci.kind == "interface" else ci.extends[:1] + ci.implements)
    for sname in sups:
        fq = sname.split("<")[0].strip().replace("!", "")
        if fq in seen:
            continue
        seen.add(fq)
        sc = all_classes.get(fq)
        if sc is not None:
            ancestors(sc, all_classes, seen)
    return seen


def inherited_keys(ci, all_classes):
    keys = set()
    for fq in ancestors(ci, all_classes):
        sc = all_classes.get(fq)
        if sc is not None:
            keys |= member_keys(sc)
        if fq in ("java.lang.Comparable", "kotlin.Comparable"):
            keys.add(("m", "compareTo", 1))
    return keys


def add_override(line, key, inherited):
    if key in inherited and " override " not in f" {line}":
        if " fun " in f" {line}" and "(" in line:
            body_i = line.rfind("): ")
            if body_i > 0:
                line = line[:body_i].replace(" = TODO()", "") + line[body_i:]
        line = line.replace("open ", "", 1) if line.startswith("open ") else line
        line = line.replace("abstract ", "", 1) if line.startswith("abstract ") else line
        return "override " + line
    return line


def emit_class(ci, all_classes, out, indent, outer_tparams=()):
    pad = "    " * indent
    inherited = inherited_keys(ci, all_classes)
    t_names, t_decl = parse_tparams(ci.tparams_src)
    ctx_names = list(outer_tparams) + t_names
    ctx = Ctx(ctx_names)
    is_object = any(k == "field" and re.search(rf"\b{re.escape(ci.fq)}\s+INSTANCE$", strip_annotations(b)) for k, b in ci.members)
    is_companion = ci.simple == "Companion" and "." in ci.dotted
    setters = setter_names(ci.members)
    prop_names = set()
    lines = []
    statics = []
    for kind, body in ci.members:
        if member_skipped(body):
            continue
        if kind == "property":
            b = strip_annotations(body)
            nm = split_top(b, " ")[-1].split(".")[-1]
            prop_names.add(nm)
    for kind, body in ci.members:
        if member_skipped(body):
            continue
        b = strip_annotations(body)
        is_static = " static " in f" {b} "
        if kind == "ctor":
            if ci.kind != "class" or is_object or is_companion:
                continue
            _, params, _ = parse_params(b[b.index("(") + 1: b.rindex(")")], ctx, allow_receiver=False)
            vis = "protected " if " protected " in f" {b} " else ""
            lines.append(f"{vis}constructor({', '.join(params)})" + (" : super()" if ci.kind == "class" and ci.extends and ci.extends[0].strip() not in ("java.lang.Object", "Object") else ""))
        elif kind == "method":
            name = b.split("(")[0].split()[-1]
            if ci.kind == "enum" and is_static and name in ("valueOf", "values", "getEntries"):
                continue
            if re.match(r"^(get|is)[A-Z]", name) and "(" in b and b[b.index("(") + 1:].startswith(")"):
                pn = name[3:] if name.startswith("get") else name
                pn = pn[0].lower() + pn[1:] if name.startswith("get") else name
                if pn in prop_names or name in prop_names:
                    continue
            if re.match(r"^set[A-Z]", name):
                pn = name[3].lower() + name[4:]
                if pn in prop_names or ("is" + name[3:]) in prop_names:
                    continue
            g = gen_method(body, ctx_names if not is_static else [], in_interface=(ci.kind == "interface"),
                           top_level=False, owner_abstract=ci.is_abstract or ci.kind == "interface")
            if g is None:
                continue
            if is_static and not is_object and not is_companion:
                statics.append(g.replace("open ", "").replace("abstract ", ""))
            else:
                if is_object or is_companion:
                    g = g.replace("open ", "")
                inner_params = b[b.index("(") + 1: b.rindex(")")]
                n = len([x for x in split_top(inner_params) if "kotlin.coroutines.Continuation" not in x]) if inner_params.strip() else 0
                g = add_override(g, ("m", name, n), inherited)
                lines.append(g)
        elif kind == "property":
            is_static_p = " static " in f" {b} "
            g = gen_property(body, ctx_names, in_interface=(ci.kind == "interface"), top_level=False,
                             setters=setters, owner_abstract=ci.is_abstract or ci.kind == "interface")
            if g is None:
                continue
            if is_static_p and not is_object and not is_companion:
                statics.append(g.replace("open ", ""))
            else:
                if is_object or is_companion:
                    g = g.replace("open ", "")
                g = add_override(g, ("p", split_top(b, " ")[-1].split(".")[-1]), inherited)
                lines.append(g)
        elif kind == "field":
            if re.search(r"\b(INSTANCE|Companion)$", b.split(" = ")[0].strip()):
                continue
            g = gen_field(body, ctx_names)
            if g is None:
                continue
            if is_object or is_companion or ci.kind == "interface":
                (statics if ci.kind == "interface" else lines).append(g)
            else:
                statics.append(g)
        elif kind == "enum_constant":
            pass

    # header
    supers = []
    if ci.kind == "class" and ci.extends:
        sup = ci.extends[0].strip()
        if sup not in ("java.lang.Object", "Object"):
            supers.append(kt_type(sup, ctx))
    for i in (ci.implements if ci.kind != "interface" else ci.extends):
        if i in ("java.lang.Object",):
            continue
        supers.append(kt_type(i, ctx))
    if ci.kind == "enum":
        supers = [s for s in supers if not s.startswith("Enum") and not s.startswith("Comparable")]
    sup_txt = (" : " + ", ".join(supers)) if supers else ""
    tps = render_tparams(t_decl, Ctx(ctx_names))
    name = esc(ci.simple)
    if ci.kind == "annotation":
        if ci.fq == "androidx.compose.runtime.Composable":
            out.append(f"{pad}@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FILE)")
        elif ci.simple.startswith("Experimental") or ci.simple.endswith("Api"):
            out.append(f"{pad}@RequiresOptIn")
        out.append(f"{pad}annotation class {name}")
        return
    if is_companion:
        out.append(f"{pad}companion object{sup_txt} {{")
    elif is_object:
        out.append(f"{pad}object {name}{sup_txt} {{")
    elif ci.kind == "interface":
        fun = "fun " if ci.is_fun and sum(1 for l in lines if " fun " in f" {l}" and "TODO()" not in l) == 1 \
            and not any((" val " in f" {l}" or " var " in f" {l}") and "TODO()" not in l for l in lines) else ""
        out.append(f"{pad}{fun}interface {name}{tps}{sup_txt} {{")
    elif ci.kind == "enum":
        out.append(f"{pad}enum class {name}{sup_txt} {{")
        consts = [strip_annotations(b).split()[-1] for k, b in ci.members if k == "enum_constant"]
        out.append(f"{pad}    " + ", ".join(esc(c) for c in consts) + ";")
    else:
        inner = "inner " if (indent > 0 and not ci.is_static and ci.kind == "class") else ""
        mod = "abstract " if ci.is_abstract else ("" if ci.is_final else "open ")
        out.append(f"{pad}{mod}{inner}class {name}{tps}{sup_txt} {{")
        has_noarg = any(re.match(r"^(protected )?constructor\(\)", l) for l in lines)
        has_super = ci.extends and ci.extends[0].strip() not in ("java.lang.Object", "Object")
        if not has_noarg:
            lines.insert(0, ("protected constructor()" if not ci.is_final else "private constructor()") + (" : super()" if has_super else ""))
    seen_lines = set()
    deduped = []
    for l in lines:
        if l in seen_lines:
            continue
        seen_lines.add(l)
        deduped.append(l)
    lines = deduped
    for l in lines:
        if ci.kind == "enum" and l.startswith("constructor") or (ci.kind == "enum" and "constructor(" in l):
            continue
        if ci.is_final or ci.kind == "enum":
            l = l.replace("open ", "", 1) if l.startswith("open ") else l
        out.append(f"{pad}    {l}")
    for child in sorted(ci.children, key=lambda c: c.simple):
        if child.simple == "Companion":
            continue
        emit_class(child, all_classes, out, indent + 1, ctx_names if (not child.is_static) else ())
    companion = next((c for c in ci.children if c.simple == "Companion"), None)
    if companion is not None or statics:
        if companion is not None:
            sub = []
            emit_class(companion, all_classes, sub, indent + 1)
            # merge statics into the companion body
            if statics:
                sub.insert(len(sub) - 1, "\n".join(f"{pad}        {s}" for s in statics))
            out.extend(sub)
        else:
            out.append(f"{pad}    companion object {{")
            for s in statics:
                out.append(f"{pad}        {s}")
            out.append(f"{pad}    }}")
    out.append(f"{pad}}}")


def is_facade(ci):
    if ci.kind != "class" or "." in ci.dotted:
        return False
    if " value class " in f" {strip_annotations(ci.header)} ":
        return False
    if any(k == "field" and strip_annotations(b).split(" = ")[0].strip().endswith(" Companion") for k, b in ci.members):
        return False
    if any(k == "ctor" for k, b in ci.members if not member_skipped(b)):
        return False
    live = [(k, b) for k, b in ci.members if not member_skipped(b)]
    if not live:
        return ci.simple.endswith("Kt")
    if any(k == "field" and b.strip().endswith(" INSTANCE") for k, b in live):
        return False
    return all(" static " in f" {strip_annotations(b)} " for k, b in live)


def main():
    out_dir = sys.argv[1]
    classes = parse_files(sys.argv[2:])
    by_pkg = defaultdict(list)
    for ci in classes.values():
        if "." in ci.dotted:
            parent_fq = f"{ci.pkg}.{ci.dotted.rsplit('.', 1)[0]}"
            parent = classes.get(parent_fq)
            if parent is not None:
                parent.children.append(ci)
                continue
        by_pkg[ci.pkg].append(ci)
    os.makedirs(out_dir, exist_ok=True)
    count = 0
    for pkg, items in by_pkg.items():
        out = [f"@file:Suppress(\"unused\", \"UNUSED_PARAMETER\")", f"package {pkg}", ""]
        for ci in sorted(items, key=lambda c: c.simple):
            if "." in ci.dotted:
                continue  # orphan nested class (parent not public)
            if is_facade(ci):
                setters = setter_names(ci.members)
                for kind, body in ci.members:
                    if member_skipped(body):
                        continue
                    if kind == "method":
                        name = strip_annotations(body).split("(")[0].split()[-1]
                        g = gen_method(body, [], in_interface=False, top_level=True, owner_abstract=False)
                    elif kind == "property":
                        g = gen_property(body, [], in_interface=False, top_level=True, setters=setters, owner_abstract=False)
                    elif kind == "field":
                        g = gen_field(body, [])
                    else:
                        g = None
                    if g and g not in out:
                        out.append(g)
                        count += 1
                continue
            emit_class(ci, classes, out, 0)
            count += 1
        fn = os.path.join(out_dir, pkg.replace(".", "_") + ".kt")
        with open(fn, "w", encoding="utf-8") as f:
            f.write("\n".join(out) + "\n")
    print(f"generated {count} top-level declarations in {len(by_pkg)} packages")


if __name__ == "__main__":
    main()
