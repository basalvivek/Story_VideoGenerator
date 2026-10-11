package com.moviemaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moviemaker.pipeline.CharacterAnimator;
import com.moviemaker.pipeline.CharacterCast;
import com.moviemaker.pipeline.CharacterCast.Kind;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterTests {

    @Test
    void detectsEnglishCharactersInOrderOfMention() {
        assertEquals(List.of(Kind.KING, Kind.LION, Kind.BIRD),
                CharacterCast.detect("The king and a lion walked by the river while a bird flew over.", 3));
    }

    @Test
    void detectsHindiCharacters() {
        assertEquals(List.of(Kind.KING, Kind.QUEEN, Kind.MONKEY),
                CharacterCast.detect("राजा और रानी ने बंदर को देखा", 3));
    }

    @Test
    void specificRoleReplacesGenericPerson() {
        assertEquals(List.of(Kind.FARMER), CharacterCast.detect("The farmer said he was tired.", 3));
    }

    @Test
    void fallsBackToOneWandererWhenNobodyIsNamed() {
        assertEquals(List.of(Kind.MAN), CharacterCast.detect("Rain fell on the quiet hills.", 3));
        assertEquals(List.of(Kind.WOMAN), CharacterCast.detect("Rain fell and she looked up.", 3));
    }

    @Test
    void fatherSpeaksToFourListeningSons() {
        var st = CharacterCast.stage("The father called his four sons and said: stay united.");
        assertTrue(st.conversation());
        assertEquals(5, st.members().size());
        assertEquals(Kind.FATHER, st.members().get(0).kind());
        assertTrue(st.members().get(0).speaker());
        assertEquals(4, st.members().stream().filter(m -> m.kind() == Kind.BOY && !m.speaker()).count());
    }

    @Test
    void hindiNumbersAndSpeaker() {
        var st = CharacterCast.stage("पिता ने अपने चार बेटों से कहा कि मिलकर रहो।");
        assertTrue(st.conversation());
        assertEquals(Kind.FATHER, st.members().get(0).kind());
        assertTrue(st.members().get(0).speaker());
        assertEquals(4, st.members().stream().filter(m -> m.kind() == Kind.BOY).count());
    }

    @Test
    void noSpeechMeansStrolling() {
        assertTrue(!CharacterCast.stage("The mother and her two daughters walked along the river.").conversation());
    }

    @Test
    void conversationFramesShowSpeakerMouthMoving() {
        var st = CharacterCast.stage("The father called his four sons and said: stay united.");
        CharacterAnimator a = new CharacterAnimator(st, 0);
        BufferedImage f1 = new BufferedImage(CharacterAnimator.W, CharacterAnimator.H, BufferedImage.TYPE_INT_ARGB);
        BufferedImage f2 = new BufferedImage(CharacterAnimator.W, CharacterAnimator.H, BufferedImage.TYPE_INT_ARGB);
        a.draw(f1, 1.0);
        a.draw(f2, 1.2);
        assertTrue(opaquePixels(f1) > 20000, "five people should be drawn");
        assertTrue(!java.util.Arrays.equals(f1.getRGB(0, 0, 1280, 720, null, 0, 1280), f2.getRGB(0, 0, 1280, 720, null, 0, 1280)));
    }

    @Test
    void animatorDrawsAndMovesCharacters() {
        CharacterAnimator a = new CharacterAnimator(List.of(Kind.PRINCESS, Kind.DOG), 0);
        BufferedImage f1 = new BufferedImage(CharacterAnimator.W, CharacterAnimator.H, BufferedImage.TYPE_INT_ARGB);
        BufferedImage f2 = new BufferedImage(CharacterAnimator.W, CharacterAnimator.H, BufferedImage.TYPE_INT_ARGB);
        a.draw(f1, 1.0);
        a.draw(f2, 4.0);
        assertTrue(opaquePixels(f1) > 2000, "characters should be drawn");
        assertTrue(!java.util.Arrays.equals(f1.getRGB(0, 0, 1280, 720, null, 0, 1280), f2.getRGB(0, 0, 1280, 720, null, 0, 1280)),
                "frames should differ over time");
    }

    private static int opaquePixels(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y += 2)
            for (int x = 0; x < img.getWidth(); x += 2)
                if ((img.getRGB(x, y) >>> 24) > 0) n++;
        return n;
    }
}
