package com.github.sup3rmar10.searchindex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A fully synthetic, seeded corpus with latent topics, used by the demo and the evaluation.
 *
 * <p>Every document belongs to one of four topics (each with 12 made-up-for-this-purpose topical words)
 * and is either "focused" (about 30% topical words, grade 2) or "passing" (about 6%, grade 1). Every token
 * also has a 3% chance of being a word from a different topic (distractor noise); the rest is filler.
 * Relevance of a document to a query about topic T is its grade if the document's topic is T, else 0.
 *
 * <p>The labels come from the generator, not from people. Results on this data show that the harness
 * works, not how the engine would do on real text.
 */
public final class SyntheticCorpus {

    public record Doc(String id, String text, int topic, int grade) {
    }

    public record LabelledQuery(String text, int topic) {
    }

    public static final String[][] TOPICS = {
            {"anchor", "harbor", "keel", "mast", "rudder", "tide", "sail", "boom", "jib", "buoy", "galley", "compass"},
            {"orbit", "telescope", "nebula", "comet", "galaxy", "eclipse", "planet", "lunar", "quasar", "meteor", "aperture", "zenith"},
            {"simmer", "whisk", "saute", "braise", "dough", "ladle", "skillet", "broth", "garnish", "marinade", "zest", "knead"},
            {"compost", "trowel", "seedling", "mulch", "pruning", "trellis", "perennial", "sprout", "loam", "bulb", "greenhouse", "weeding"}};

    public static final String[] TOPIC_NAMES = {"sailing", "astronomy", "cooking", "gardening"};

    private static final String[] FILLER = {
            "the", "a", "of", "and", "to", "in", "is", "it", "that", "with", "for", "on", "was", "as", "at",
            "this", "by", "an", "be", "from", "are", "or", "one", "all", "when", "there", "some", "more",
            "often", "slowly", "every", "after", "before", "small", "large", "old", "new", "early", "late", "good"};

    private static final double FOCUSED_DENSITY = 0.30;
    private static final double PASSING_DENSITY = 0.06;
    private static final double DISTRACTOR_RATE = 0.03;

    private SyntheticCorpus() {
    }

    public static List<Doc> generate(int count, long seed) {
        Random rnd = new Random(seed);
        List<Doc> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int topic = i % TOPICS.length;
            int grade = rnd.nextInt(3) == 0 ? 2 : 1;
            double density = grade == 2 ? FOCUSED_DENSITY : PASSING_DENSITY;
            int length = 20 + rnd.nextInt(100);
            StringBuilder sb = new StringBuilder();
            for (int t = 0; t < length; t++) {
                double r = rnd.nextDouble();
                String word;
                if (r < density) {
                    word = TOPICS[topic][rnd.nextInt(TOPICS[topic].length)];
                } else if (r < density + DISTRACTOR_RATE) {
                    int other = (topic + 1 + rnd.nextInt(TOPICS.length - 1)) % TOPICS.length;
                    word = TOPICS[other][rnd.nextInt(TOPICS[other].length)];
                } else {
                    word = FILLER[rnd.nextInt(FILLER.length)];
                }
                if (t > 0) {
                    sb.append(' ');
                }
                sb.append(word);
            }
            docs.add(new Doc(String.format("doc-%03d", i), sb.toString(), topic, grade));
        }
        return docs;
    }

    /** Queries of two distinct topical words each, {@code perTopic} for every topic. */
    public static List<LabelledQuery> queries(int perTopic, long seed) {
        Random rnd = new Random(seed);
        List<LabelledQuery> out = new ArrayList<>();
        for (int topic = 0; topic < TOPICS.length; topic++) {
            for (int q = 0; q < perTopic; q++) {
                int a = rnd.nextInt(TOPICS[topic].length);
                int b = (a + 1 + rnd.nextInt(TOPICS[topic].length - 1)) % TOPICS[topic].length;
                out.add(new LabelledQuery(TOPICS[topic][a] + " " + TOPICS[topic][b], topic));
            }
        }
        return out;
    }

    /** Graded relevance labels (doc id to grade) for one query; documents of other topics are omitted (grade 0). */
    public static Map<String, Integer> grades(List<Doc> docs, LabelledQuery query) {
        Map<String, Integer> grades = new HashMap<>();
        for (Doc d : docs) {
            if (d.topic() == query.topic()) {
                grades.put(d.id(), d.grade());
            }
        }
        return grades;
    }
}
