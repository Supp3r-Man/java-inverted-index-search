#!/usr/bin/env python
"""Hand-rolled mutation check: apply ONE textual mutation at a time, run the unit tests, record
whether at least one test failed, then restore the original file.

Usage:  python scripts/mutation_check.py            (all mutations)
        python scripts/mutation_check.py M05 M10    (selected ids)
        python scripts/mutation_check.py --check    (only verify every `old` text occurs exactly once)
Set MVN to the Maven executable if `mvn` is not on PATH.

Each mutation replaces the single occurrence of `old` with `new`. If `old` does not occur exactly once
the mutation is reported as BROKEN (the script is out of date), never as a survivor.
Files are restored from their original bytes in a finally block, even on Ctrl-C.
Every test has a 10 s timeout (src/test/resources/junit-platform.properties), so a mutant that
loops forever is killed by a timeout failure instead of hanging the run.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "src", "main", "java", "com", "github", "sup3rmar10", "searchindex")
MVN = os.environ.get("MVN", "mvn")

MUTATIONS = [
    ("M01", "idf ignores document frequency", "Bm25.java",
     "(docCount - docFreq + 0.5) / (docFreq + 0.5)", "(docCount + 0.5) / (docFreq + 0.5)"),
    ("M02", "length normalisation switched off", "Bm25.java",
     "double norm = 1.0 - b + b * ratio;", "double norm = 1.0;"),
    ("M03", "no term-frequency saturation (linear in tf)", "Bm25.java",
     "return tf * (k1 + 1.0) / (tf + k1 * norm);", "return tf * (k1 + 1.0) / (1.0 + k1 * norm);"),
    ("M04", "analyzer does not lowercase", "Analyzer.java",
     ".toLowerCase(Locale.ROOT)", ""),
    ("M05", "positions count kept tokens only (stopword gap lost)", "Analyzer.java",
     "out.add(new Token(stemming ? SimpleStemmer.stem(raw) : raw, position));",
     "out.add(new Token(stemming ? SimpleStemmer.stem(raw) : raw, out.size()));"),
    ("M06", "stemming flag ignored", "Analyzer.java",
     "stemming ? SimpleStemmer.stem(raw) : raw", "raw"),
    ("M07", "no Unicode NFC normalisation", "Analyzer.java",
     "Normalizer.normalize(text, Normalizer.Form.NFC)", "text"),
    ("M08", "combining marks end a token", "Analyzer.java",
     "return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;", "return false;"),
    ("M09", "lookup by external id also finds tombstoned docs", "Segment.java",
     "return d != null && isLive(d) ? d : -1;", "return d != null ? d : -1;"),
    ("M10", "live doc frequency counts tombstoned docs", "Segment.java",
     "if (isLive(p.doc())) {\n                df++;", "if (true) {\n                df++;"),
    ("M11", "withDeleted mutates the shared tombstone set", "Segment.java",
     "BitSet copy = (BitSet) deleted.clone();", "BitSet copy = deleted;"),
    ("M12", "merge keeps tombstoned documents", "Segment.java",
     "if (seg.isLive(d)) {", "if (true) {"),
    ("M13", "merge does not renumber postings", "Segment.java",
     "add(p.withDoc(nd));", "add(p);"),
    ("M14", "phrase offsets ignore stopword gaps", "SearchIndex.java",
     "offsets[i] = tokens.get(i).position() - tokens.get(0).position();", "offsets[i] = i;"),
    ("M15", "phrase terms need not be adjacent", "SearchIndex.java",
     "match = others[i].hasPosition(start + offsets[i]);", "match = true;"),
    ("M16", "AND ignores NOT exclusions", "SearchIndex.java",
     "boolean inAll = !excluded.contains(e.getKey());", "boolean inAll = true;"),
    ("M17", "OR takes the max score instead of the sum", "SearchIndex.java",
     "union.merge(e.getKey(), e.getValue(), Double::sum);", "union.merge(e.getKey(), e.getValue(), Math::max);"),
    ("M18", "BM25 sees tf = 1 for every match", "SearchIndex.java",
     "bm25.score(idf, p.freq(),", "bm25.score(idf, 1,"),
    ("M19", "N counts tombstoned documents", "SearchIndex.java",
     "n += s.liveDocs();\n                total += s.liveLength();", "n += s.maxDoc();\n                total += s.liveLength();"),
    ("M20", "delete does not tombstone anything", "SearchIndex.java",
     "segments.set(i, segments.get(i).withDeleted(local));", "segments.get(i).withDeleted(local);"),
    ("M21", "search does not refresh the write buffer", "SearchIndex.java",
     "        flush();\n        Ctx ctx = new Ctx(List.copyOf(segments));", "        Ctx ctx = new Ctx(List.copyOf(segments));"),
    ("M22", "top-k heap keeps the worst hits", "TopK.java",
     "BEST_FIRST.compare(h, heap.peek()) < 0", "BEST_FIRST.compare(h, heap.peek()) > 0"),
    ("M23", "top-k ties broken by id descending", "TopK.java",
     "return c != 0 ? c : a.id().compareTo(b.id());", "return c != 0 ? c : b.id().compareTo(a.id());"),
    ("M24", "delete-ratio threshold is exclusive", "SizeTieredMergePolicy.java",
     "s.deleteRatio() >= maxDeleteRatio", "s.deleteRatio() > maxDeleteRatio"),
    ("M25", "bare NOT parses as OR NOT instead of AND NOT", "QueryParser.java",
     "(peek().kind() == Kind.AND || peek().kind() == Kind.NOT)", "(peek().kind() == Kind.AND)"),
]


def run_tests():
    try:
        p = subprocess.run([MVN, "-B", "-q", "test"], cwd=ROOT, capture_output=True, text=True, timeout=600)
    except subprocess.TimeoutExpired:
        return "timeout", [], 0
    out = p.stdout + p.stderr
    if "COMPILATION ERROR" in out:
        return "compile-error", [], 0
    failing = sorted(set(re.findall(r"\[ERROR\]\s{2,}(\w+Test\.\w+)", out)))
    return ("killed" if p.returncode != 0 else "survived"), failing, len(failing)


def read_text(path):
    with open(path, "rb") as f:
        raw = f.read()
    return raw, raw.decode("utf-8").replace("\r\n", "\n")


def main():
    args = sys.argv[1:]
    check_only = "--check" in args
    wanted = {a for a in args if not a.startswith("--")}
    originals = {}
    results = []
    try:
        for mid, desc, fname, old, new in MUTATIONS:
            if wanted and mid not in wanted:
                continue
            path = os.path.join(SRC, fname)
            if fname not in originals:
                originals[fname], _ = read_text(path)
            text = originals[fname].decode("utf-8").replace("\r\n", "\n")
            count = text.count(old)
            if count != 1:
                results.append((mid, desc, f"BROKEN (pattern found {count} times)", []))
                print(f"{mid} {desc}: BROKEN (pattern found {count} times)", flush=True)
                continue
            if check_only:
                print(f"{mid} ok", flush=True)
                continue
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text.replace(old, new, 1))
            status, failing, n = run_tests()
            with open(path, "wb") as f:
                f.write(originals[fname])
            label = {"killed": "KILLED", "survived": "SURVIVED", "compile-error": "COMPILE ERROR (invalid mutant)",
                     "timeout": "KILLED (timeout)"}[status]
            results.append((mid, desc, label, failing))
            print(f"{mid} | {desc} | {label} | {n} failing: {', '.join(failing[:3])}", flush=True)
    finally:
        for fname, data in originals.items():
            with open(os.path.join(SRC, fname), "wb") as f:
                f.write(data)
    if not check_only:
        killed = sum(1 for r in results if r[2].startswith("KILLED"))
        survived = [r[0] for r in results if r[2] == "SURVIVED"]
        print(f"\n{killed}/{len(results)} mutations killed; survivors: {survived or 'none'}")


if __name__ == "__main__":
    main()
