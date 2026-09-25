package ar.com.biopus.abismocam;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class CaptureSequenceTest {
    private static final class Events implements CaptureSequence.Listener {
        final List<String> events = new ArrayList<>();
        public void onOsc(int v) { events.add("osc:" + v); }
        public void onCount(int n) { if (n > 0) events.add("count:" + n); }
        public void onCapture() { events.add("capture"); }
        public void onLive() { events.add("live"); }
        public void onTimeout() { events.add("timeout"); }
    }

    @Test public void fiveFullSecondsThenZeroAndExactlyOneCapture() {
        Events e = new Events(); CaptureSequence s = new CaptureSequence(e);
        assertTrue(s.start(100));
        s.tick(1099); assertEquals(Arrays.asList("osc:1", "count:5"), e.events);
        for (int n = 1; n <= 4; n++) s.tick(100 + n * 1000);
        s.tick(5099); assertEquals(CaptureSequence.State.COUNTDOWN, s.state());
        s.tick(5100); s.tick(5150);
        assertEquals(Arrays.asList("osc:1", "count:5", "count:4", "count:3", "count:2", "count:1", "osc:0", "capture"), e.events);
    }

    @Test public void extraClicksBlockedThroughCountdownFlashAndOneSecondReview() {
        Events e = new Events(); CaptureSequence s = new CaptureSequence(e);
        s.start(0); assertFalse(s.start(100));
        s.tick(5000); assertFalse(s.start(5001));
        assertTrue(s.frameReady(5050)); assertFalse(s.frameReady(5051));
        s.tick(5050 + CaptureSequence.FLASH_MS + 999);
        assertTrue(s.isBusy()); assertFalse(s.start(6200));
        s.tick(5050 + CaptureSequence.FLASH_MS + 1000);
        assertFalse(s.isBusy()); assertEquals("live", e.events.get(e.events.size() - 1));
        assertTrue(s.start(7000));
    }

    @Test public void cancellationAlwaysClearsOscAndNeverCapturesLate() {
        Events e = new Events(); CaptureSequence s = new CaptureSequence(e);
        s.start(0); s.tick(1000); s.cancel(); s.cancel(); s.tick(6000);
        assertEquals(Arrays.asList("osc:1", "count:5", "count:4", "osc:0", "live"), e.events);
        assertFalse(s.frameReady(6001));
    }

    @Test public void lateUiTickDoesNotExtendTheCountdown() {
        Events e = new Events(); CaptureSequence s = new CaptureSequence(e);
        s.start(0); s.tick(3300); s.tick(5300);
        assertEquals(Arrays.asList("osc:1", "count:5", "count:2", "osc:0", "capture"), e.events);
    }

    @Test public void cameraTimeoutUnlocksButtonWithoutSecondZero() {
        Events e = new Events(); CaptureSequence s = new CaptureSequence(e);
        s.start(0); s.tick(5000); s.tick(7500); s.cancel();
        assertFalse(s.isBusy()); assertFalse(s.frameReady(7600));
        assertEquals(Arrays.asList("osc:1", "count:5", "osc:0", "capture", "timeout", "live"), e.events);
    }
}
