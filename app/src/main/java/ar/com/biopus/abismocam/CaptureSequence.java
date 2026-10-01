package ar.com.biopus.abismocam;

/** Reloj monotónico inyectado: independiente de Android y de la transmisión NDI. */
final class CaptureSequence {
    static final int FLASH_MS = 160;
    static final int FROZEN_MS = 1000;
    enum State { IDLE, COUNTDOWN, AWAITING_FRAME, FROZEN }
    interface Listener {
        void onOsc(int value);
        void onCount(int seconds);
        void onCapture();
        void onLive();
        void onTimeout();
    }
    private final Listener listener;
    private State state = State.IDLE;
    private long deadline;
    private int number;

    CaptureSequence(Listener listener) { this.listener = listener; }
    State state() { return state; }
    boolean isBusy() { return state != State.IDLE; }

    boolean start(long now) { return start(now, 5); }

    boolean start(long now, int seconds) {
        if (isBusy()) return false;
        if (seconds < 0 || seconds > 15) throw new IllegalArgumentException("Cuenta fuera de rango");
        state = State.COUNTDOWN;
        deadline = now + seconds * 1000L;
        number = seconds;
        listener.onOsc(1);
        if (seconds > 0) listener.onCount(seconds);
        else tick(now);
        return true;
    }

    void tick(long now) {
        if (state == State.COUNTDOWN) {
            long remaining = deadline - now;
            if (remaining <= 0) {
                state = State.AWAITING_FRAME;
                deadline = now + 2500;
                listener.onOsc(0);
                listener.onCount(0);
                listener.onCapture();
            } else {
                int next = (int)((remaining + 999) / 1000);
                if (next != number) { number = next; listener.onCount(next); }
            }
        } else if (state == State.FROZEN && now >= deadline) {
            state = State.IDLE;
            listener.onLive();
        } else if (state == State.AWAITING_FRAME && now >= deadline) {
            state = State.IDLE;
            listener.onTimeout();
            listener.onLive();
        }
    }

    boolean frameReady(long now) {
        if (state != State.AWAITING_FRAME) return false;
        state = State.FROZEN;
        deadline = now + FLASH_MS + FROZEN_MS;
        return true;
    }

    void cancel() {
        if (!isBusy()) return;
        boolean wasCounting = state == State.COUNTDOWN;
        state = State.IDLE;
        if (wasCounting) listener.onOsc(0);
        listener.onCount(0);
        listener.onLive();
    }
}
