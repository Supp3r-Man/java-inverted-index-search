# java-inverted-index-search

A small in-memory full-text search engine in Java 17: analyzer, inverted index with token positions, BM25 ranking,
boolean and phrase queries, immutable segments with tombstone deletes and a merge policy, a query parser with
positioned errors, and heap-based top-k. It exists to show how the pieces under Lucene/Elasticsearch fit together,
not to replace them. The only dependency is JUnit 5 (test scope). All data in the demo, tests and evaluation is
synthetic.

What it does:

- **Analyzer:** Unicode NFC, lowercase (`Locale.ROOT`), split on every code point that is not a letter, digit or
  combining mark. Optional stopwords and an optional, documented, deliberately crude suffix stemmer
  (`SimpleStemmer`, not Porter). Positions count raw tokens, so a removed stopword leaves a gap and phrases keep their distance.
- **Inverted index with positions:** `term -> postings (doc, sorted positions)` per segment.
- **BM25** with configurable `k1` and `b` (defaults 1.2 and 0.75), idf in the non-negative `ln(1 + ...)` form.
- **Queries:** `AND`, `OR`, `NOT`, parentheses, `"quoted phrases"` (matched with positions). Adjacency means OR, a bare
  `NOT` after a clause means `AND NOT`, precedence is `NOT` > `AND` > `OR`. Errors carry the character position.
- **Segments:** immutable. A delete is a tombstone bit on a new `Segment` object that shares the data. A `MergePolicy`
  (`SizeTieredMergePolicy`) rewrites segments, dropping tombstones. Ranking statistics use live documents only.
- **Top-k** with a bounded min-heap; ties broken by document id so results do not depend on insertion order or segment layout.
- **Demo and tiny evaluation** (nDCG@10, recall@30) on a seeded synthetic corpus. See the honesty note below.

## How to run

```bash
mvn -q -B verify                                                # compile, run the tests, build the jar
java -cp target/classes com.github.sup3rmar10.searchindex.Demo  # scripted demo (same on Windows, no classpath separator needed)
java -jar target/java-inverted-index-search-0.1.0.jar           # the same demo from the jar
```

Library use:

```java
Analyzer analyzer = Analyzer.standard().withStopwords(Analyzer.ENGLISH_STOPWORDS).withStemming(true);
SearchIndex index = new SearchIndex(analyzer, new Bm25(1.2, 0.75), new SizeTieredMergePolicy(10, 0.5), 1000);
index.addDocument("a", "The quick brown fox jumps over the lazy dog");
index.search("\"lazy dog\" OR fox NOT afternoon", 10);   // List<Hit>(id, score), best first
index.deleteDocument("a");                                // tombstone, invisible to search immediately
index.forceMerge();                                       // physically drops tombstones
```

## Query language

```
query   := or
or      := and ( [OR] and )*          adjacency means OR:  cat dog  ==  cat OR dog
and     := unary ( AND unary | NOT unary )*     "cat NOT dog"  ==  cat AND NOT dog
unary   := NOT unary | primary
primary := WORD | "quoted phrase" | ( or )
```

Operators are upper-case only (`and` is an ordinary word). Consequence of the precedence: `a b AND c` means
`a OR (b AND c)`. `NOT` works as set complement, so `NOT x` alone returns every other document with score 0, and
`a OR NOT b` is the union of `a` with the complement of `b` (documents reached through a complement are unscored).
A query word that analyses to nothing (a stopword) is dropped from the query; if nothing is left the result is empty.
A word that analyses to several tokens (`state-of-art`) is treated as a phrase.

Parse errors (`QueryParseException`, with `position()`):

```
fox AND        -> AND at the end of the query has no term after it (at position 4)
"lazy dog      -> unterminated phrase: missing closing quote (at position 0)
(fox OR dog    -> missing ')' to close the '(' opened here (at position 0)
fox ) dog      -> unmatched ')' (at position 4)
AND fox        -> unexpected AND: operators need a term on both sides (at position 0)
```

## Demo output (real run)

```
== 1. a few hand-written SYNTHETIC sentences (stopwords removed, stemming on)
query: fox dog
  s4   1.935  The dog barks at the fox, the fox ignores the dog
  s1   1.329  The quick brown fox jumps over the lazy dog
  s2   1.183  A lazy dog sleeps while the quick fox is running over hills
query: hills NOT quick
  s6   0.816  Hills, rivers and lazy afternoons
  s3   0.708  Brown bears and brown foxes live in the hills
query: "lazy dog"
  s1   1.329  The quick brown fox jumps over the lazy dog
  s2   1.183  A lazy dog sleeps while the quick fox is running over hills
query: "over the lazy"
  s1   1.652  The quick brown fox jumps over the lazy dog
...
== 2. deletes are tombstones until a merge rewrites the segment
after deleting s1: segments=[3, 3] live=5 tombstones=1
query: "lazy dog"
  s2   1.478  A lazy dog sleeps while the quick fox is running over hills
after forceMerge:  segments=[5] live=5 tombstones=0
...
== 4. generated SYNTHETIC corpus: 80 docs, 4 invented topics, seed 7
segments=[40, 40] live=80
query: orbit telescope
  doc-001  score=6.149  tokens=108  (generator topic: astronomy, grade 2)
  doc-073  score=5.665  tokens=50  (generator topic: astronomy, grade 2)
  doc-077  score=5.658  tokens=37  (generator topic: astronomy, grade 1)
...
```

(Abbreviated; sections 1 and 4 have more queries, section 3 prints the parse errors shown above. The score of `s2` changes
from 1.183 to 1.478 after `s1` is deleted because N, the document frequencies and the average length changed: deleted
documents stop counting immediately.) Notice that `foxes` in `s3` is stemmed to `foxe` by the crude stemmer, so
it does not match `fox`; that is the stemmer's documented weakness, not a bug in the index.

## Design note: why BM25 saturation and length normalisation matter

```
idf(N, df)          = ln(1 + (N - df + 0.5) / (df + 0.5))
tfNorm(tf, dl, avg) = tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / avg))
score               = idf * tfNorm        (summed over matching query terms)
```

- **Saturation (`k1`).** `tfNorm` rises with `tf` but is bounded by `k1 + 1`. The second occurrence of a word is strong
  evidence, the tenth adds little, so repeating a keyword (stuffing) has diminishing returns. `k1 = 0` makes a term binary;
  a huge `k1` approaches raw term frequency.
- **Length normalisation (`b`).** The denominator contains `dl / avg`. A document longer than average gets a larger
  denominator, so for the same `tf` it scores lower; a shorter one scores higher. Without it (`b = 0`) long documents win
  just for having more chances to contain the word. `b = 1` is full normalisation. `b` is a dial, not a truth: long
  documents that really are about the topic are penalised too.
- **How these are tested** (`Bm25Test`, `RankingPropertyTest`): longer document with the same term count scores strictly lower
  across a sweep of lengths, in the pure formula and through the index; `b = 0` gives identical scores for any length;
  a larger `b` penalises the long document more; marginal gain of each extra occurrence strictly shrinks, and scores stay
  below `idf * (k1 + 1)`; five repeats of one term lose to one occurrence each of two terms.

Other design choices:

- **Statistics over live documents.** N, df and average length ignore tombstoned documents immediately. As far as I know
  Lucene keeps counting them until a merge (not checked against its source here). The benefit here: a merge cannot change a score,
  and that is a property test on random corpora with random deletes. The cost: `liveDocFreq` walks the postings, O(df) per
  term per query.
- **Tie-break by id** makes results independent of insertion order and segment layout, so those tests are exact comparisons.
- **Snapshot per search:** a search copies the segment list; there is no locking because the class is not thread-safe.

## Evaluation (synthetic; read this before quoting any number)

`SyntheticCorpus` generates 120 documents from a seeded `java.util.Random`: four invented topics with 12 topical words
each, documents either "focused" (about 30% topical words, grade 2) or "passing" (about 6%, grade 1), 3% distractor words
from other topics, the rest filler. Relevance to a query about topic T is the document's grade if its topic is T, else 0.
**The labels come from the same generator that wrote the text.** It validates the metric code and shows how the
settings behave on this generator; it says nothing about real relevance. 12 queries (two topical words each).

```
configuration                                   nDCG@10  recall@30
BM25 k1=1.2 b=0.75                                0.867      0.600
BM25 k1=1.2 b=0 (no length normalisation)         0.861      0.592
BM25 k1=0 (term present or not)                   0.789      0.592
near-raw tf (k1=1e6, b=0)                         0.865      0.592
baseline: first 30 ids, ignores the query         0.154      0.250
```

recall is at 30 because each query has 30 relevant documents, so recall@10 could never exceed 0.33. The differences
between the first, second and fourth rows are small and I would not read anything into them; the clear signals are
that the engine is far above the no-skill baseline and that the binary-term setting is lowest.

## Tests

178 JUnit 5 tests, deterministic (seeded `Random`, no sleeps, no clocks, no network), a few seconds in total. Every test
has a 10 second timeout via `junit-platform.properties`. `RankingPropertyTest` holds the property-style checks: random corpora
and parameter sweeps for length normalisation, saturation, top-k being a prefix of the full ranking, merge and delete
neutrality (before and after `forceMerge`, and against a fresh index of the survivors), insertion-order independence, and
`AND` being a subset of `OR` and phrase a subset of `AND`.

## Mutation check

Passing tests prove little if they would also pass on broken code, so I applied 25 behaviour-defining mutations one at a time
(single textual replacements run by `scripts/mutation_check.py`, each `old` text required to occur exactly once, files restored
in a `finally` block), ran `mvn test` for each, and recorded whether any test failed. This is a hand-rolled check, not PIT, and the
mutations are the ones I thought of, so it says nothing about bugs I did not think of.

**Result: first full run 24 of 25 killed, 1 survived (M08). I added a test for the survivor and re-ran M08 (killed). Final: 25 of 25 killed,
0 survivors.** Only M08 and M17 were re-run after the test changes (see notes); the other 23 results are from the first full run on the
suite before those two tests were added.

| # | Mutation | Result | First failing test(s) |
|---|---|---|---|
| M01 | idf ignores document frequency | killed | `Bm25Test.idfMatchesTheFormulaForKnownValues`, 1 more |
| M02 | length normalisation switched off | killed | `Bm25Test.bOneNormalisesFullyAndLargerBPenalisesLongDocsMore`, 6 more |
| M03 | no tf saturation (linear in tf) | killed | `Bm25Test.averageLengthDocumentReducesToTheClassicFormula`, 6 more |
| M04 | analyzer does not lowercase | killed | `AnalyzerTest.lowercasesTokens`, 3 more |
| M05 | positions count kept tokens only (stopword gap lost) | killed | `AnalyzerTest.removedStopwordsLeaveAGapInPositions`, 1 more |
| M06 | stemming flag ignored | killed | `AnalyzerTest.stemmingIsOptionalAndOffByDefault`, 1 more |
| M07 | no Unicode NFC normalisation | killed | `AnalyzerTest.composedAndDecomposedAccentsGiveTheSameToken` (only this one) |
| M08 | combining marks end a token | **survived, then killed** | first run: none failed. After the new test: `AnalyzerTest.combiningMarkWithoutAPrecomposedFormStaysInsideItsToken` |
| M09 | lookup by external id also finds tombstoned docs | killed | `SearchIndexTest.deletedDocumentsDisappearImmediately`, 2 more |
| M10 | live doc frequency counts tombstoned docs | killed | `RankingPropertyTest.mergingAndPhysicalDeletionNeverChangeAnyScoreOrOrder`, 3 more |
| M11 | `withDeleted` mutates the shared tombstone set | killed | `SegmentTest.deletingReturnsANewSegmentAndLeavesTheOriginalUntouched`, 1 more |
| M12 | merge keeps tombstoned documents | killed | `SearchIndexTest.aFullyDeletedSegmentDisappearsOnForceMerge`, 6 more |
| M13 | merge does not renumber postings | killed | `RankingPropertyTest.mergingAndPhysicalDeletionNeverChangeAnyScoreOrOrder`, 6 more |
| M14 | phrase offsets ignore stopword gaps | killed | `SearchIndexTest.phraseGapsFromStopwordsAreRespected` (only this one) |
| M15 | phrase terms need not be adjacent | killed | `SearchIndexTest.phraseRequiresAdjacencyAndOrder`, 3 more |
| M16 | AND ignores NOT exclusions | killed | `SearchIndexTest.notExcludesDocumentsAndBareNotMeansAndNot` (only this one) |
| M17 | OR takes the max score instead of the sum | **killed by one test, then strengthened** | first run: only `RankingPropertyTest.fiveRepeatsOfOneTermDoNotBeatOneOccurrenceOfEachOfTwoTerms`; now also `SearchIndexTest.orAndAndScoresAreTheSumOfTheClauseScores` |
| M18 | BM25 sees tf = 1 for every match | killed | `RankingPropertyTest.repeatedTermsSaturateInTheIndex`, 1 more |
| M19 | N counts tombstoned documents | killed | `SearchIndexTest.deletedDocumentsDoNotInfluenceScoresOfTheRest`, 1 more |
| M20 | delete does not tombstone anything | killed | 8 tests, e.g. `EvaluationAndDemoTest.demoShowsTombstonesBeingReclaimedAndPositionedErrors`, `RankingPropertyTest.mergingAndPhysicalDeletionNeverChangeAnyScoreOrOrder` |
| M21 | search does not refresh the write buffer | killed | 34 tests |
| M22 | top-k heap keeps the worst hits | killed | `TopKTest.aTieForTheLastSlotKeepsTheSmallerId`, 5 more |
| M23 | top-k ties broken by id descending | killed | `SearchIndexTest.equalScoresAreOrderedByIdWhateverTheInsertionOrder`, 6 more |
| M24 | delete-ratio threshold exclusive instead of inclusive | killed | `MergePolicyTest.aSegmentWithEnoughTombstonesIsRewrittenAlone`, 1 more |
| M25 | bare NOT parses as OR NOT instead of AND NOT | killed | `QueryParserTest.aBareNotAfterAClauseMeansAndNot`, 1 more |

Notes on the results:

- **M08 was a genuine test gap.** My only combining-mark test used `e` + U+0301, which NFC normalisation composes into a single
  `é` before tokenizing, so the combining-mark branch never ran. The branch only matters for marks with no precomposed form
  (for example `x` + U+0301). I added `combiningMarkWithoutAPrecomposedFormStaysInsideItsToken` and re-ran M08: killed.
- **M17 was killed by a single, indirect test.** Nothing asserted directly that OR sums its clause scores. I added
  `orAndAndScoresAreTheSumOfTheClauseScores` and re-ran M17: still killed, now by two tests.
- **M14, M16 and M07 are each killed by one test only**, so those behaviours rest on one assertion each.
- **Not re-run after the two added tests:** the remaining 23 mutants. Adding tests can only add failures, so a killed mutant stays
  killed, but I did not repeat the full run. The final `mvn -q -B verify` is green and `--check` confirmed every source file still
  contains each original pattern exactly once, i.e. no mutation was left behind.
- The stemmer's `-ing`, `-ed` and `ies` rules are covered by `SimpleStemmerTest` but are not mutation-tested here; only the on/off flag is (M06).

Reproduce: `python scripts/mutation_check.py` (set `MVN` to the Maven executable if `mvn` is not on PATH; `--check` only verifies the patterns).
It took roughly 12 minutes for 25 mutants on my machine.

## Known limits / not verified

- Everything is on the heap and uses plain objects (`List<Posting>`, small `int[]`). No compression, skip lists, persistence or
  on-disk format. Performance and memory were never measured; no benchmark exists.
- Not thread-safe. Merges run synchronously inside `add`/`delete`/`flush`. Searching flushes the write buffer first.
- Phrase matching starts from the first term's postings, not the rarest. Phrase score uses the phrase frequency and the summed idf of its terms (a simple choice).
- Scores of results that come through a `NOT` complement inside an `OR`, and of purely negative queries, are 0.
- `SimpleStemmer` is crude and over-/under-stems (`hoping` collides with `hop`, `foxes` becomes `foxe`, irregular forms untouched).
  No accent folding, no apostrophe handling (`don't` becomes `don`, `t`), no CJK segmentation (a CJK run is one token).
- No fields, fuzzy/prefix/wildcard queries, highlighting or query-time boosts.
- Evaluation is synthetic with generator-made labels; the corpus is random word salad, so phrase relevance is only unit-tested.
- Tested only on JDK 17 with Maven 3.9.9 on Windows. I have not compared scores with Lucene or any other engine.
- CI: the workflow is included; CI not yet confirmed.
- Not production-ready; a learning and showcase project.

## Layout

```
src/main/java/com/github/sup3rmar10/searchindex/   Analyzer, SimpleStemmer, Bm25, Segment, Posting, SearchIndex, QueryParser, Query,
                                                   MergePolicy, SizeTieredMergePolicy, TopK, Metrics, SyntheticCorpus, Evaluation, Demo
src/test/java/...                                  unit tests and RankingPropertyTest (property-style, seeded)
scripts/mutation_check.py                          the mutation check described above
```

Clone: `git clone https://github.com/Supp3r-Man/java-inverted-index-search.git`

MIT licensed.
