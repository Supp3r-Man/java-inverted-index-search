package com.github.sup3rmar10.searchindex;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An immutable piece of the index: documents numbered 0..maxDoc-1, a term to postings map (postings
 * sorted by doc, positions sorted), document lengths and the stored text. Deleting a document does not
 * touch any of that; {@link #withDeleted(int)} returns a new Segment that shares the data and carries
 * a larger tombstone set. Tombstoned documents are skipped by the live-doc accessors and are dropped
 * for real by {@link #merge(long, List)}.
 */
public final class Segment {

    private record Data(List<String> docIds, List<String> texts, int[] lengths,
                        Map<String, List<Posting>> postings, Map<String, Integer> idToLocal) {
    }

    private final long id;
    private final Data data;
    private final BitSet deleted;
    private final int liveCount;
    private final long liveLength;

    private Segment(long id, Data data, BitSet deleted) {
        this.id = id;
        this.data = data;
        this.deleted = deleted;
        int live = 0;
        long length = 0;
        for (int d = 0; d < data.docIds().size(); d++) {
            if (!deleted.get(d)) {
                live++;
                length += data.lengths()[d];
            }
        }
        this.liveCount = live;
        this.liveLength = length;
    }

    /** Analyses and indexes the documents (external id, text) in order. Ids must be unique. */
    static Segment build(long id, List<Map.Entry<String, String>> docs, Analyzer analyzer) {
        int n = docs.size();
        List<String> ids = new ArrayList<>(n);
        List<String> texts = new ArrayList<>(n);
        int[] lengths = new int[n];
        Map<String, Integer> idToLocal = new HashMap<>();
        Map<String, List<Posting>> postings = new TreeMap<>();
        for (int d = 0; d < n; d++) {
            Map.Entry<String, String> doc = docs.get(d);
            if (idToLocal.put(doc.getKey(), d) != null) {
                throw new IllegalArgumentException("duplicate document id in one segment: " + doc.getKey());
            }
            ids.add(doc.getKey());
            texts.add(doc.getValue());
            List<Token> tokens = analyzer.analyze(doc.getValue());
            lengths[d] = tokens.size();
            Map<String, List<Integer>> byTerm = new TreeMap<>();
            for (Token t : tokens) {
                byTerm.computeIfAbsent(t.term(), k -> new ArrayList<>()).add(t.position());
            }
            for (Map.Entry<String, List<Integer>> e : byTerm.entrySet()) {
                int[] pos = e.getValue().stream().mapToInt(Integer::intValue).toArray();
                postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new Posting(d, pos));
            }
        }
        return freeze(id, ids, texts, lengths, postings, idToLocal);
    }

    /** Rewrites the live documents of the inputs (in order) into one new segment without tombstones. */
    static Segment merge(long id, List<Segment> inputs) {
        List<String> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        Map<String, Integer> idToLocal = new HashMap<>();
        int[][] remap = new int[inputs.size()][];
        for (int s = 0; s < inputs.size(); s++) {
            Segment seg = inputs.get(s);
            remap[s] = new int[seg.maxDoc()];
            for (int d = 0; d < seg.maxDoc(); d++) {
                if (seg.isLive(d)) {
                    remap[s][d] = ids.size();
                    idToLocal.put(seg.docId(d), ids.size());
                    ids.add(seg.docId(d));
                    texts.add(seg.text(d));
                    lengths.add(seg.length(d));
                } else {
                    remap[s][d] = -1;
                }
            }
        }
        Map<String, List<Posting>> postings = new TreeMap<>();
        for (int s = 0; s < inputs.size(); s++) {
            for (Map.Entry<String, List<Posting>> e : inputs.get(s).data.postings().entrySet()) {
                for (Posting p : e.getValue()) {
                    int nd = remap[s][p.doc()];
                    if (nd >= 0) {
                        postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(p.withDoc(nd));
                    }
                }
            }
        }
        return freeze(id, ids, texts, lengths.stream().mapToInt(Integer::intValue).toArray(), postings, idToLocal);
    }

    private static Segment freeze(long id, List<String> ids, List<String> texts, int[] lengths,
                                  Map<String, List<Posting>> postings, Map<String, Integer> idToLocal) {
        Map<String, List<Posting>> frozen = new TreeMap<>();
        for (Map.Entry<String, List<Posting>> e : postings.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        Data data = new Data(List.copyOf(ids), List.copyOf(texts), lengths,
                Collections.unmodifiableMap(frozen), Map.copyOf(idToLocal));
        return new Segment(id, data, new BitSet());
    }

    /** A new segment, sharing all data with this one, in which {@code localDoc} is also tombstoned. */
    public Segment withDeleted(int localDoc) {
        if (localDoc < 0 || localDoc >= maxDoc()) {
            throw new IndexOutOfBoundsException("no such document: " + localDoc);
        }
        BitSet copy = (BitSet) deleted.clone();
        copy.set(localDoc);
        return new Segment(id, data, copy);
    }

    public long id() {
        return id;
    }

    /** Number of documents ever written to this segment, including tombstoned ones. */
    public int maxDoc() {
        return data.docIds().size();
    }

    public int liveDocs() {
        return liveCount;
    }

    public int deletedDocs() {
        return maxDoc() - liveCount;
    }

    /** Fraction of documents that are tombstoned (0 for an empty segment). */
    public double deleteRatio() {
        return maxDoc() == 0 ? 0.0 : (double) deletedDocs() / maxDoc();
    }

    public boolean isLive(int localDoc) {
        return !deleted.get(localDoc);
    }

    /** Sum of the lengths (token counts) of the live documents. */
    public long liveLength() {
        return liveLength;
    }

    public String docId(int localDoc) {
        return data.docIds().get(localDoc);
    }

    public String text(int localDoc) {
        return data.texts().get(localDoc);
    }

    public int length(int localDoc) {
        return data.lengths()[localDoc];
    }

    /** Local doc number of the live document with this external id, or -1. */
    public int localDoc(String externalId) {
        Integer d = data.idToLocal().get(externalId);
        return d != null && isLive(d) ? d : -1;
    }

    public Set<String> terms() {
        return data.postings().keySet();
    }

    /** All postings for the term, including those of tombstoned documents; empty if the term is absent. */
    public List<Posting> postings(String term) {
        return data.postings().getOrDefault(term, List.of());
    }

    /** Posting for (term, doc) by binary search, or null. */
    public Posting posting(String term, int localDoc) {
        List<Posting> list = postings(term);
        int lo = 0;
        int hi = list.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int d = list.get(mid).doc();
            if (d < localDoc) {
                lo = mid + 1;
            } else if (d > localDoc) {
                hi = mid - 1;
            } else {
                return list.get(mid);
            }
        }
        return null;
    }

    /** Number of live documents containing the term. */
    public int liveDocFreq(String term) {
        int df = 0;
        for (Posting p : postings(term)) {
            if (isLive(p.doc())) {
                df++;
            }
        }
        return df;
    }
}
