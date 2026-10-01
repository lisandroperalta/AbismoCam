package ar.com.biopus.abismocam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Disparador accesible. Solamente ACTION_UP de un toque válido produce un clic. */
final class ShutterButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pointer = -1;
    private boolean armed;

    ShutterButton(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Capturar foto");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        paint.setColor(Color.WHITE);
        paint.setAlpha(isEnabled() ? 255 : 100);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.035f);
        canvas.drawCircle(cx, cy, size * 0.46f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, size * (armed ? 0.33f : 0.385f), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                armed = true; setPressed(true); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index < 0 || event.getX(index) < 0 || event.getX(index) > getWidth()
                    || event.getY(index) < 0 || event.getY(index) > getHeight()) cancelTouch();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) cancelTouch();
                return true;
            case MotionEvent.ACTION_UP:
                boolean click = armed && event.getPointerId(event.getActionIndex()) == pointer;
                cancelTouch();
                if (click) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelTouch(); return true;
            default: return true;
        }
    }

    void cancelTouch() { pointer = -1; armed = false; setPressed(false); invalidate(); }

    @Override public boolean performClick() {
        if (!isEnabled()) return false;
        super.performClick();
        return true;
    }
    @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
    @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); cancelTouch(); }
}
