#!/usr/bin/env python3
"""Error-driven pruning of generated stubs: comments out member lines kotlinc rejects and
strips supertypes from rejected class headers. Exit 1 when nothing could be pruned."""
import re, sys
from collections import defaultdict
errs = defaultdict(set)
msgs = defaultdict(list)
for line in open(sys.argv[1]):
    m = re.match(r"^(.*\.kt):(\d+):\d+: error: (.*)", line)
    if m:
        errs[m.group(1)].add(int(m.group(2)))
        msgs[(m.group(1), int(m.group(2)))].append(m.group(3))
changed = 0
for path, lines in errs.items():
    src = open(path).read().split("\n")
    for ln in sorted(lines):
        i = ln - 1
        if i >= len(src):
            continue
        s = src[i]
        st = s.strip()
        if st.startswith("// PRUNED"):
            continue
        ms = " ".join(msgs.get((path, ln), []))
        if "needs an 'override' modifier" in ms and " override " not in f" {st}" and len(errs[path]) and "hides member" in ms:
            body_i = s.rfind("): ")
            fixed = s[:body_i].replace(" = TODO()", "") + s[body_i:] if (" fun " in f" {s}" and body_i > 0) else s
            indent = s[: len(s) - len(s.lstrip())]
            fixed = fixed.strip()
            for mod in ("open ", "abstract "):
                if fixed.startswith(mod):
                    fixed = fixed[len(mod):]
            src[i] = indent + "override " + fixed
            changed += 1
            continue
        if "overrides nothing" in ms and st.startswith("override "):
            src[i] = s.replace("override ", "", 1)
            changed += 1
            continue
        if "not allowed to specify default values" in ms:
            body_i = s.rfind("): ")
            if body_i > 0 and " = TODO()" in s[:body_i]:
                src[i] = s[:body_i].replace(" = TODO()", "") + s[body_i:]
                changed += 1
                continue
        is_header = re.match(r"^\s*(.*\b(class|interface|object)\b)[^{]*\{\s*$", s) is not None
        if is_header:
            # find a ':' at angle-bracket depth 0 after the declaration keyword
            depth, colon = 0, -1
            start = max(s.find(" class "), s.find("interface "), s.find("object "), s.find("class "))
            for k in range(start, len(s)):
                ch = s[k]
                if ch in "<(":
                    depth += 1
                elif ch in ">)":
                    depth -= 1
                elif ch == ":" and depth == 0:
                    colon = k
                    break
            if colon >= 0:
                unresolved = re.findall(r"unresolved reference '([^']+)'", ms)
                supers_txt = s[colon + 1: s.rindex("{")].strip()
                parts, depth, cur = [], 0, ""
                for ch in supers_txt:
                    if ch in "<(":
                        depth += 1
                    elif ch in ">)":
                        depth -= 1
                    if ch == "," and depth == 0:
                        parts.append(cur.strip()); cur = ""
                    else:
                        cur += ch
                if cur.strip():
                    parts.append(cur.strip())
                here = msgs.get((path, ln), [])
                not_impl = any("does not implement abstract" in m for m in here)
                only_known = all(("unresolved reference" in m) or ("does not implement abstract" in m) for m in here)
                if only_known:
                    if not_impl:
                        s = re.sub(r"^(\s*)(?:open |final )?(?:(inner) )?class ", lambda mm: f"{mm.group(1)}abstract {mm.group(2) + ' ' if mm.group(2) else ''}class ", s, count=1)
                    keep = [p_ for p_ in parts if not any(re.search(rf"\b{re.escape(u)}\b", p_) for u in unresolved)]
                    colon = s.index(":", s.index("class ") if "class " in s else 0) if ":" in s else colon
                    # recompute colon in the possibly rewritten line
                    depth, colon = 0, -1
                    for k in range(s.find("class "), len(s)):
                        ch = s[k]
                        if ch in "<(":
                            depth += 1
                        elif ch in ">)":
                            depth -= 1
                        elif ch == ":" and depth == 0:
                            colon = k
                            break
                else:
                    keep = []
                src[i] = s[:colon].rstrip() + ((" : " + ", ".join(keep)) if keep else "") + " {"
                changed += 1 if src[i] != s else 0
            continue
        if st in ("}", "") or st.startswith("package ") or st.startswith("@file"):
            continue
        else:
            src[i] = s[: len(s) - len(s.lstrip())] + "// PRUNED " + st
            changed += 1
    open(path, "w").write("\n".join(src))
sys.exit(0 if changed else 1)
