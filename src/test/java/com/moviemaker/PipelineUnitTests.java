package com.moviemaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moviemaker.pipeline.StoryPlanner;
import com.moviemaker.pipeline.StoryPlanner.ScenePlan;
import com.moviemaker.pipeline.SubtitleBuilder;
import com.moviemaker.pipeline.TextExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineUnitTests {
    private final StoryPlanner planner = new StoryPlanner();

    private static String story(int sentences) {
        StringBuilder sb = new StringBuilder("Early Life\n");
        for (int i = 0; i < sentences; i++) {
            sb.append("Sentence number ").append(i).append(" tells of the prince who walked beside the quiet river.");
            sb.append(i % 5 == 4 ? "\n\n" : " ");
        }
        return sb.toString();
    }

    @Test
    void plansExpectedSceneCountAndTitles() {
        List<ScenePlan> plan = planner.plan(story(60), 5);
        assertEquals(6, plan.size());
        assertEquals("Introduction", plan.get(0).title());
        assertEquals("Conclusion", plan.get(5).title());
        plan.forEach(p -> assertFalse(p.narration().isBlank()));
    }

    @Test
    void trimsNarrationToBudget() {
        List<ScenePlan> plan = planner.plan(story(600), 1);
        int words = plan.stream().mapToInt(p -> p.narration().split("\\s+").length).sum();
        assertTrue(words <= 150, "words=" + words);
    }

    @Test
    void shortStoryGivesFewerScenes() {
        List<ScenePlan> plan = planner.plan("The sun rose over the hills. Birds began to sing.", 30);
        assertEquals(2, plan.size());
    }

    @Test
    void emptyTextGivesNoScenes() {
        assertTrue(planner.plan("   \n  ", 5).isEmpty());
    }

    @Test
    void subtitlesAreContiguousAndFormatted() {
        var cues = SubtitleBuilder.cues("One two three. Four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen.", 0.5, 10);
        assertTrue(cues.size() >= 3);
        assertEquals(0.5, cues.get(0).start(), 1e-9);
        assertEquals(10.5, cues.get(cues.size() - 1).end(), 1e-6);
        assertTrue(SubtitleBuilder.srt(cues).startsWith("1\n00:00:00,500 --> "));
    }

    @Test
    void detectsFormatsByMagicBytes() {
        assertEquals(TextExtractor.Format.PDF, TextExtractor.detect("%PDF-1.7 xxxx".getBytes(), "a.pdf"));
        assertNull(TextExtractor.detect("hello world, not a doc".getBytes(), "a.pdf"));
    }
}
