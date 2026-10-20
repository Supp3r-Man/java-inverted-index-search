package com.github.sup3rmar10.searchindex;

import com.github.sup3rmar10.searchindex.Query.AndQuery;
import com.github.sup3rmar10.searchindex.Query.NotQuery;
import com.github.sup3rmar10.searchindex.Query.OrQuery;
import com.github.sup3rmar10.searchindex.Query.PhraseQuery;
import com.github.sup3rmar10.searchindex.Query.TermQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The in-memory index: a write buffer plus a list of immutable {@link Segment}s.
 *
 * <ul>
 *   <li>{@link #addDocument} buffers; the buffer becomes a segment on {@link #flush()}, when it reaches
 *       the flush threshold, or automatically before a search. Re-adding an id replaces the document.</li>
 *   <li>{@link #deleteDocument} tombstones the document (or drops it from the buffer). Tombstoned
 *       documents are invisible to search and to the BM25 statistics immediately, and physically
 *       removed when a merge rewrites their segment.</li>
 *   <li>After each flush or delete the {@link MergePolicy} is consulted until it has nothing to merge.</li>
 *   <li>Ranking statistics (N, document frequency, average length) are computed over live documents
 *       of all segments, so merging never changes a score.</li>
 * </ul>
 *
 * Not thread-safe: callers must serialise access.
 */
public final class SearchIndex {

    public static final int DEFAULT_FLUSH_THRESHOLD = 1000;

    private final Analyzer analyzer;
    private final Bm25 bm25;
    private final MergePolicy mergePolicy;
    private final int flushThreshold;
    private final List<Segment> segments = new ArrayList<>();
    private final Map<String, String> buffer = new LinkedHashMap<>();
    private long nextSegmentId = 1;

    public SearchIndex() {
        this(Analyzer.standard(), Bm25.defaults(), new SizeTieredMergePolicy(10, 0.5), DEFAULT_FLUSH_THRESHOLD);
    }

    public SearchIndex(Analyzer analyzer, Bm25 bm25, MergePolicy mergePolicy, int flushThreshold) {
        if (analyzer == null || bm25 == null || mergePolicy == null) {
            throw new IllegalArgumentException("analyzer, bm25 and mergePolicy must not be null");
        }
        if (flushThreshold <= 0) {
            throw new IllegalArgumentException("flushThreshold must be positive, was " + flushThreshold);
        }
        this.analyzer = analyzer;
        this.bm25 = bm25;
        this.mergePolicy = mergePolicy;
        this.flushThreshold = flushThreshold;
    }

    // ---------------------------------------------------------------- writing

    public void addDocument(String id, String text) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("document id must not be empty");
        }
        if (text == null) {
            throw new IllegalArgumentException("document text must not be null");
        }
        deleteDocument(id);
        buffer.put(id, text);
        if (buffer.size() >= flushThreshold) {
            flush();
        }
    }

    /** @return true if a live document with this id existed. */
    public boolean deleteDocument(String id) {
        if (buffer.remove(id) != null) {
            return true;
        }
        for (int i = 0; i < segments.size(); i++) {
            int local = segments.get(i).localDoc(id);
            if (local >= 0) {
                segments.set(i, segments.get(i).withDeleted(local));
                maybeMerge();
                return true;
            }
        }
        return false;
    }

    /** Turns buffered documents into a new immutable segment, then lets the merge policy run. */
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<Map.Entry<String, String>> docs = new ArrayList<>(buffer.entrySet());
        buffer.clear();
        segments.add(Segment.build(nextSegmentId++, docs, analyzer));
        maybeMerge();
    }

    /** Runs the merge policy until it selects nothing. */
    public void maybeMerge() {
        while (true) {
            List<Segment> picked = mergePolicy.select(Collections.unmodifiableList(segments));
            if (picked.isEmpty()) {
                return;
            }
            merge(picked);
        }
    }

    /** Flushes and merges everything into a single segment without tombstones. */
    public void forceMerge() {
        flush();
        if (segments.size() > 1 || (segments.size() == 1 && segments.get(0).deletedDocs() > 0)) {
            merge(List.copyOf(segments));
        }
    }

    private void merge(List<Segment> picked) {
        Set<Segment> pickedSet = Collections.newSetFromMap(new IdentityHashMap<>());
        pickedSet.addAll(picked);
        if (pickedSet.size() != picked.size()) {
            throw new IllegalStateException("merge policy returned the same segment twice");
        }
        // merge in index order so the document order inside the result is stable
        List<Segment> ordered = new ArrayList<>();
        int first = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (pickedSet.contains(segments.get(i))) {
                ordered.add(segments.get(i));
                if (first < 0) {
                    first = i;
                }
            }
        }
        if (ordered.size() != picked.size()) {
            throw new IllegalStateException("merge policy returned a segment that is not in the index");
        }
        Segment merged = Segment.merge(nextSegmentId++, ordered);
        segments.removeIf(pickedSet::contains);
        if (merged.maxDoc() > 0) {
            segments.add(first, merged);
        }
    }

    // ---------------------------------------------------------------- introspection

    public int segmentCount() {
        return segments.size();
    }

    /** Live documents in segments, plus buffered ones. */
    public int liveDocCount() {
        int n = buffer.size();
        for (Segment s : segments) {
            n += s.liveDocs();
        }
        return n;
    }

    /** Tombstoned documents still physically present in segments. */
    public int tombstoneCount() {
        int n = 0;
        for (Segment s : segments) {
            n += s.deletedDocs();
        }
        return n;
    }

    public int bufferedDocCount() {
        return buffer.size();
    }

    /** maxDoc of each segment, in index order. */
    public List<Integer> segmentSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (Segment s : segments) {
            sizes.add(s.maxDoc());
        }
        return sizes;
    }

    /** Stored text of a live document, or null. */
    public String document(String id) {
        String buffered = buffer.get(id);
        if (buffered != null) {
            return buffered;
        }
        for (Segment s : segments) {
            int local = s.localDoc(id);
            if (local >= 0) {
                return s.text(local);
            }
        }
        return null;
    }

    public Analyzer analyzer() {
        return analyzer;
    }

    public Bm25 bm25() {
        return bm25;
    }

    // ---------------------------------------------------------------- searching

    public List<Hit> search(String queryText, int k) {
        return search(QueryParser.parse(queryText), k);
    }

    public List<Hit> search(Query query, int k) {
        TopK top = new TopK(k);
        flush();
        Ctx ctx = new Ctx(List.copyOf(segments));
        if (ctx.docCount == 0) {
            return List.of();
        }
        Match m = eval(query, ctx);
        if (m == null) {
            return List.of();
        }
        if (!m.negated) {
            for (Map.Entry<Long, Double> e : m.docs.entrySet()) {
                top.offer(ctx.idOf(e.getKey()), e.getValue());
            }
        } else {
            for (int s = 0; s < ctx.segs.size(); s++) {
                Segment seg = ctx.segs.get(s);
                for (int d = 0; d < seg.maxDoc(); d++) {
                    if (seg.isLive(d) && !m.docs.containsKey(key(s, d))) {
                        top.offer(seg.docId(d), 0.0);
                    }
                }
            }
        }
        return top.toSortedList();
    }

    private static long key(int segment, int doc) {
        return ((long) segment << 32) | (doc & 0xFFFFFFFFL);
    }

    /** Statistics snapshot for one search. */
    private final class Ctx {
        final List<Segment> segs;
        final long docCount;
        final double avgLength;
        private final Map<String, Double> idfCache = new HashMap<>();

        Ctx(List<Segment> segs) {
            this.segs = segs;
            long n = 0;
            long total = 0;
            for (Segment s : segs) {
                n += s.liveDocs();
                total += s.liveLength();
            }
            this.docCount = n;
            this.avgLength = n == 0 ? 0.0 : (double) total / n;
        }

        double idf(String term) {
            return idfCache.computeIfAbsent(term, t -> {
                long df = 0;
                for (Segment s : segs) {
                    df += s.liveDocFreq(t);
                }
                return bm25.idf(docCount, df);
            });
        }

        String idOf(long key) {
            return segs.get((int) (key >>> 32)).docId((int) key);
        }
    }

    /** Result of evaluating a node: scored documents, or (when negated) the set of excluded documents. */
    private record Match(boolean negated, Map<Long, Double> docs) {
    }

    /** @return null when the node analyses to nothing (e.g. only stopwords) and so takes no part in the query. */
    private Match eval(Query q, Ctx ctx) {
        if (q instanceof TermQuery t) {
            return text(t.text(), ctx);
        } else if (q instanceof PhraseQuery p) {
            return text(p.text(), ctx);
        } else if (q instanceof AndQuery a) {
            List<Match> parts = evalAll(a.clauses(), ctx);
            return parts.isEmpty() ? null : and(parts);
        } else if (q instanceof OrQuery o) {
            List<Match> parts = evalAll(o.clauses(), ctx);
            return parts.isEmpty() ? null : or(parts);
        } else if (q instanceof NotQuery n) {
            Match inner = eval(n.clause(), ctx);
            return inner == null ? null : negate(inner);
        }
        throw new IllegalStateException("unknown query node " + q);
    }

    private List<Match> evalAll(List<Query> clauses, Ctx ctx) {
        List<Match> out = new ArrayList<>();
        for (Query c : clauses) {
            Match m = eval(c, ctx);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    private Match text(String raw, Ctx ctx) {
        List<Token> tokens = analyzer.analyze(raw);
        if (tokens.isEmpty()) {
            return null;
        }
        return tokens.size() == 1 ? termMatch(tokens.get(0).term(), ctx) : phraseMatch(tokens, ctx);
    }

    private Match termMatch(String term, Ctx ctx) {
        double idf = ctx.idf(term);
        Map<Long, Double> scores = new HashMap<>();
        for (int s = 0; s < ctx.segs.size(); s++) {
            Segment seg = ctx.segs.get(s);
            for (Posting p : seg.postings(term)) {
                if (seg.isLive(p.doc())) {
                    scores.put(key(s, p.doc()), bm25.score(idf, p.freq(), seg.length(p.doc()), ctx.avgLength));
                }
            }
        }
        return new Match(false, scores);
    }

    private Match phraseMatch(List<Token> tokens, Ctx ctx) {
        int n = tokens.size();
        String[] terms = new String[n];
        int[] offsets = new int[n];
        double idf = 0;
        for (int i = 0; i < n; i++) {
            terms[i] = tokens.get(i).term();
            offsets[i] = tokens.get(i).position() - tokens.get(0).position();
            idf += ctx.idf(terms[i]);
        }
        Map<Long, Double> scores = new HashMap<>();
        for (int s = 0; s < ctx.segs.size(); s++) {
            Segment seg = ctx.segs.get(s);
            for (Posting first : seg.postings(terms[0])) {
                int doc = first.doc();
                if (!seg.isLive(doc)) {
                    continue;
                }
                Posting[] others = new Posting[n];
                boolean all = true;
                for (int i = 1; i < n && all; i++) {
                    others[i] = seg.posting(terms[i], doc);
                    all = others[i] != null;
                }
                if (!all) {
                    continue;
                }
                int count = 0;
                for (int j = 0; j < first.freq(); j++) {
                    int start = first.position(j);
                    boolean match = true;
                    for (int i = 1; i < n && match; i++) {
                        match = others[i].hasPosition(start + offsets[i]);
                    }
                    if (match) {
                        count++;
                    }
                }
                if (count > 0) {
                    scores.put(key(s, doc), bm25.score(idf, count, seg.length(doc), ctx.avgLength));
                }
            }
        }
        return new Match(false, scores);
    }

    private static Match negate(Match m) {
        // NOT of a positive set is "everything except these"; NOT of a complement is the positive set (unscored)
        if (!m.negated) {
            Map<Long, Double> excluded = new HashMap<>();
            for (Long k : m.docs.keySet()) {
                excluded.put(k, 0.0);
            }
            return new Match(true, excluded);
        }
        return new Match(false, m.docs);
    }

    private static Match and(List<Match> parts) {
        List<Match> pos = new ArrayList<>();
        Set<Long> excluded = new HashSet<>();
        for (Match m : parts) {
            if (m.negated) {
                excluded.addAll(m.docs.keySet());
            } else {
                pos.add(m);
            }
        }
        if (pos.isEmpty()) {
            return new Match(true, zeroes(excluded));
        }
        Map<Long, Double> out = new HashMap<>();
        for (Map.Entry<Long, Double> e : pos.get(0).docs.entrySet()) {
            double sum = e.getValue();
            boolean inAll = !excluded.contains(e.getKey());
            for (int i = 1; i < pos.size() && inAll; i++) {
                Double other = pos.get(i).docs.get(e.getKey());
                if (other == null) {
                    inAll = false;
                } else {
                    sum += other;
                }
            }
            if (inAll) {
                out.put(e.getKey(), sum);
            }
        }
        return new Match(false, out);
    }

    private static Match or(List<Match> parts) {
        Map<Long, Double> union = new HashMap<>();
        Set<Long> excluded = null;
        for (Match m : parts) {
            if (m.negated) {
                if (excluded == null) {
                    excluded = new HashSet<>(m.docs.keySet());
                } else {
                    excluded.retainAll(m.docs.keySet());
                }
            } else {
                for (Map.Entry<Long, Double> e : m.docs.entrySet()) {
                    union.merge(e.getKey(), e.getValue(), Double::sum);
                }
            }
        }
        if (excluded == null) {
            return new Match(false, union);
        }
        // NOT x OR y = NOT (x AND NOT y): documents of the positive clauses are rescued from the exclusion set
        excluded.removeAll(union.keySet());
        return new Match(true, zeroes(excluded));
    }

    private static Map<Long, Double> zeroes(Set<Long> keys) {
        Map<Long, Double> m = new HashMap<>();
        for (Long k : keys) {
            m.put(k, 0.0);
        }
        return m;
    }
}
