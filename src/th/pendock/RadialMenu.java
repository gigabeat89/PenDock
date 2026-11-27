package th.pendock;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

final class RadialMenu extends FrameLayout {
    private static final PathInterpolator OPEN = new PathInterpolator(.22f, .55f, .32f, 1f);
    private static final PathInterpolator CLOSE = new PathInterpolator(.35f, 0f, .85f, .52f);
    private static final int HUB_RADIUS_DP = 44;
    private static final int OPEN_DURATION_MS = 220;

    private final String[] labels;
    private final View[] buttons;
    private final float[] highlights;
    private final int[] iconColors;
    private final Runnable[] actions;
    private final Shortcuts.Tool[] tools;
    private final Ring ring;
    private final View blurLayer;
    private final View dimLayer;
    private Bitmap blurFrame;
    private final float[] angles, directionX, directionY;
    private final int ringSize;
    private final int iconHalf, hubHalfWidth, highlightOffset;
    private final int[] screenOrigin = new int[2];
    private final Path segment = new Path();
    private final Path hubHex = new Path();
    private final Path cellHex = new Path();
    private final Region segmentHitArea = new Region();
    private final float fallbackX, fallbackY;
    private final TextView hub, batteryStatus;
    private final Runnable close;
    private final boolean penOnly, preview, requireHover, useSeparateSurface;
    private final float maxScale;
    private final String style;
    private Appearance.Palette colors;
    private final Runnable fallback = this::revealAtCenter;
    private boolean waitingForHover;
    private boolean revealed;
    private boolean opening;
    private SurfaceRingAnimation surfaceAnimation;
    private Runnable surfaceTimeout;
    private boolean hoverPending;
    private float hoverX, hoverY;
    private boolean closing;
    private int hovered = -1;
    private int pressed = -1;
    private int pressedPointer = -1;
    private float centerX, centerY;
    private float anchorX, anchorY;
    private float outerRadius, iconOrbit, scale;
    private ValueAnimator highlightAnimation;
    private ValueAnimator batteryAnimation;
    private ValueAnimator dimAnimation;
    private float dimAlpha;
    private float dimProgress;
    private boolean batteryWanted;
    private float batteryExpansion;

    RadialMenu(Context context, float fallbackX, float fallbackY, boolean useHover,
            Runnable lock, Runnable capture, Runnable circleSearch, Runnable laserMode,
            Runnable translateScreen, Runnable pinScreen, Runnable close,
            List<Shortcuts.Entry> entries, Consumer<Shortcuts.Entry> launch, String battery,
            Runnable floatActive, boolean alreadyWindowed, Runnable splitActive, boolean alreadySplit,
            Bitmap blurFrame) {
        this(context, fallbackX, fallbackY, useHover, lock, capture, circleSearch, laserMode,
                translateScreen, pinScreen, close, entries, launch, battery, floatActive, alreadyWindowed,
                splitActive, alreadySplit, blurFrame, false);
    }

    static RadialMenu preview(Context context, List<Shortcuts.Entry> entries) {
        Runnable sample = () -> { };
        return new RadialMenu(context, 0, 0, false, sample, sample, sample, sample, sample, sample, sample,
                entries, app -> { }, "—", sample, false, sample, false, null, true);
    }

    private RadialMenu(Context context, float fallbackX, float fallbackY, boolean useHover,
            Runnable lock, Runnable capture, Runnable circleSearch, Runnable laserMode,
            Runnable translateScreen, Runnable pinScreen, Runnable close,
            List<Shortcuts.Entry> entries, Consumer<Shortcuts.Entry> launch, String battery,
            Runnable floatActive, boolean alreadyWindowed, Runnable splitActive, boolean alreadySplit,
            Bitmap blurFrame, boolean preview) {
        super(context);
        this.preview = preview;
        // Huawei can slow the overlay's UI frame cadence when no PenDock Accessibility service is bound.
        useSeparateSurface = !preview && "huawei".equalsIgnoreCase(Build.MANUFACTURER)
                && !WindowModeMonitor.isReady() && !SystemScreenshot.isReady() && !CircleCaptureService.isReady();
        maxScale = RingConfig.ringScale(context);
        style = RingConfig.ringStyle(context);
        dimAlpha = RingConfig.ringDimAlpha(context);
        colors = Appearance.ringPalette(context);
        this.fallbackX = fallbackX;
        this.fallbackY = fallbackY;
        this.close = close;
        penOnly = !preview && useHover && PenInput.isPenOnlyMenu(context);
        requireHover = !preview && useHover && PenInput.requiresNearScreen(context);
        int count = entries.size();
        actions = new Runnable[count];
        tools = new Shortcuts.Tool[count];
        labels = new String[count];
        buttons = new View[count];
        highlights = new float[count];
        iconColors = new int[count];
        angles = new float[count];
        directionX = new float[count];
        directionY = new float[count];
        for (int index = 0; index < count; index++) {
            angles[index] = -90f + index * (360f / count);
            double angle = Math.toRadians(angles[index]);
            directionX[index] = (float) Math.cos(angle);
            directionY[index] = (float) Math.sin(angle);
        }
        ringSize = dp(304 * maxScale);
        iconHalf = dp(27);
        hubHalfWidth = dp(56);
        highlightOffset = dp(3);
        this.blurFrame = blurFrame;
        int blurDp = RingConfig.ringBlurDp(context);
        if (!preview && blurFrame != null && blurDp > 0 && Build.VERSION.SDK_INT >= 31) {
            Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            android.graphics.Rect dest = new android.graphics.Rect();
            android.view.WindowManager wm = context.getSystemService(android.view.WindowManager.class);
            android.graphics.Rect displayBounds = wm.getCurrentWindowMetrics().getBounds();
            blurLayer = new View(context) {
                @Override protected void onDraw(Canvas canvas) {
                    Bitmap frame = RadialMenu.this.blurFrame;
                    if (frame == null || frame.isRecycled()) return;
                    getLocationOnScreen(screenOrigin);
                    dest.set(-screenOrigin[0], -screenOrigin[1],
                            -screenOrigin[0] + displayBounds.width(),
                            -screenOrigin[1] + displayBounds.height());
                    canvas.drawBitmap(frame, null, dest, framePaint);
                }
            };
            float radius = dp(blurDp);
            blurLayer.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            blurLayer.setAlpha(0f);
            blurLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(blurLayer, new LayoutParams(-1, -1));
        } else {
            if (this.blurFrame != null) {
                this.blurFrame.recycle();
                this.blurFrame = null;
            }
            blurLayer = null;
        }
        dimLayer = new View(context);
        dimLayer.setBackgroundColor(Color.BLACK);
        dimLayer.setAlpha(0f);
        dimLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(dimLayer, new LayoutParams(-1, -1));
        ring = new Ring(context);
        ring.setVisibility(INVISIBLE);
        addView(ring, new LayoutParams(ringSize, ringSize));
        waitingForHover = useHover;
        setClickable(true);
        setFocusableInTouchMode(true);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        for (int index = 0; index < entries.size(); index++) {
            Shortcuts.Entry entry = entries.get(index);
            Shortcuts.Tool tool = entry.tool;
            tools[index] = tool;
            Runnable action = () -> launch.accept(entry);
            String label = entry.label;
            if (tool != null) {
                // The service may have cached entries before the user changed the language.
                label = context.getString(tool.label);
                switch (tool) {
                    case LOCK: action = lock; break;
                    case SCREENSHOT: action = capture; break;
                    case CIRCLE_SEARCH: action = circleSearch; break;
                    case FLOAT_WINDOW:
                        action = floatActive;
                        if (action == null) label = context.getString(alreadyWindowed
                                ? R.string.float_already : R.string.float_unavailable);
                        break;
                    case SPLIT_SCREEN:
                        action = splitActive;
                        if (action == null) label = context.getString(alreadySplit
                                ? R.string.split_already : R.string.split_unavailable);
                        break;
                    case LASER_POINTER: action = laserMode; break;
                    case TRANSLATE: action = translateScreen; break;
                    case PIN_SCREEN: action = pinScreen; break;
                }
            }
            addButton(index, entry.icon, label, action);
            if (tool == Shortcuts.Tool.SCREENSHOT) {
                buttons[index].setContentDescription(context.getString(R.string.capture_system));
            } else if (tool == Shortcuts.Tool.FLOAT_WINDOW && action == null) {
                buttons[index].setContentDescription(context.getString(alreadyWindowed
                        ? R.string.float_already_description : R.string.float_unavailable_description));
            } else if (tool == Shortcuts.Tool.SPLIT_SCREEN && action == null) {
                buttons[index].setContentDescription(context.getString(alreadySplit
                        ? R.string.split_already_description : R.string.split_unavailable_description));
            }
        }
        hub = Ui.text(context, "PenDock", 13, colors.text, true);
        hub.setGravity(Gravity.CENTER);
        hub.setSingleLine(true);
        hub.setEllipsize(TextUtils.TruncateAt.END);
        hub.setPadding(dp(11), 0, dp(11), 0);
        hub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ring.addView(hub, new LayoutParams(hubHalfWidth * 2, dp(24)));
        batteryStatus = Ui.text(context, "", 13, colors.accent, true);
        batteryStatus.setSingleLine(true);
        batteryStatus.setGravity(Gravity.CENTER);
        batteryStatus.setVisibility(INVISIBLE);
        setBattery(battery);
        ring.addView(batteryStatus, new LayoutParams(hubHalfWidth * 2, dp(22)));
        if (preview) {
            dimProgress = 1f;
            applyDim();
        }
    }

    void setDimAlpha(float alpha) {
        dimAlpha = Math.max(0f, Math.min(1f, alpha));
        if (preview) {
            dimProgress = 1f;
            applyDim();
        }
    }

    void reloadColors() {
        colors = Appearance.ringPalette(getContext());
        hub.setTextColor(colors.text);
        batteryStatus.setTextColor(colors.accent);
        java.util.Arrays.fill(iconColors, 0);
        updateArtwork();
    }

    private void applyDim() {
        dimLayer.setAlpha(dimAlpha * dimProgress);
        if (blurLayer != null) blurLayer.setAlpha(dimProgress);
    }

    private void animateDim(float target, long duration, TimeInterpolator interpolator) {
        if (dimAnimation != null) dimAnimation.cancel();
        if (dimAlpha <= 0f && blurLayer == null) {
            dimProgress = 0f;
            applyDim();
            return;
        }
        if (duration <= 0 || !ValueAnimator.areAnimatorsEnabled()) {
            dimProgress = target;
            applyDim();
            return;
        }
        dimAnimation = ValueAnimator.ofFloat(dimProgress, target);
        dimAnimation.setDuration(duration);
        dimAnimation.setInterpolator(interpolator);
        dimAnimation.addUpdateListener(animation -> {
            dimProgress = (float) animation.getAnimatedValue();
            applyDim();
        });
        dimAnimation.start();
    }

    void setBattery(String battery) {
        if (!TextUtils.equals(batteryStatus.getText(), battery)) batteryStatus.setText(battery);
        batteryStatus.setContentDescription("—".equals(battery) ? getContext().getString(R.string.pen_battery_unknown)
                : getContext().getString(R.string.pen_battery, battery));
    }

    private void addButton(int index, Drawable icon, String description, Runnable action) {
        ImageView button = new ImageView(getContext());
        button.setImageDrawable(icon);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(description);
        button.setEnabled(action != null);
        button.setFocusable(action != null);
        if (action != null) button.setOnClickListener(view -> choose(index));
        addItem(index, button, description, action);
    }

    private void addItem(int index, View view, String label, Runnable action) {
        labels[index] = label;
        actions[index] = action;
        ring.addView(view, new LayoutParams(iconHalf * 2, iconHalf * 2));
        buttons[index] = view;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;
        scale = Math.min(maxScale, Math.min(width, height) / (float) dp(304));
        outerRadius = dp(buttons.length == 0 ? HUB_RADIUS_DP : buttons.length > 5 ? 130 : 119) * scale;
        float innerRadius = dp(61) * scale;
        iconOrbit = lerp(innerRadius, outerRadius, .54f);
        if (buttons.length > 0) buildSegment(innerRadius);
        buildRoundedHexagon(hubHex, dp(HUB_RADIUS_DP + 3) * scale, dp(10) * scale);
        buildRoundedHexagon(cellHex, cellRadius() * 1.08f, dp(8) * scale);
        updateArtwork();
        if (revealed) place(anchorX, anchorY);
        else if (preview || hasWindowFocus()) prepareReveal();
    }

    private void buildSegment(float innerRadius) {
        float halfStep = 180f / buttons.length;
        float outerAngle = halfStep - degreesForGap(dp(5) * scale, outerRadius);
        float innerAngle = halfStep - degreesForGap(dp(7) * scale, innerRadius);
        float roundOuter = Math.min(outerAngle * .8f, degreesForGap(dp(10) * scale, outerRadius));
        float roundInner = Math.min(innerAngle * .8f, degreesForGap(dp(7) * scale, innerRadius));
        float outerCurve = outerAngle - roundOuter;
        float innerCurve = innerAngle - roundInner;
        float outerShoulder = outerRadius - dp(10) * scale;
        float innerShoulder = innerRadius + dp(8) * scale;

        segment.reset();
        segment.arcTo(new RectF(-outerRadius, -outerRadius, outerRadius, outerRadius),
                -90 - outerCurve, 2 * outerCurve, true);
        segment.quadTo(side(outerRadius, outerAngle), top(outerRadius, outerAngle),
                side(outerShoulder, outerAngle), top(outerShoulder, outerAngle));
        segment.lineTo(side(innerShoulder, innerAngle), top(innerShoulder, innerAngle));
        segment.quadTo(side(innerRadius, innerAngle), top(innerRadius, innerAngle),
                side(innerRadius, innerCurve), top(innerRadius, innerCurve));
        segment.arcTo(new RectF(-innerRadius, -innerRadius, innerRadius, innerRadius),
                -90 + innerCurve, -2 * innerCurve, false);
        segment.quadTo(-side(innerRadius, innerAngle), top(innerRadius, innerAngle),
                -side(innerShoulder, innerAngle), top(innerShoulder, innerAngle));
        segment.lineTo(-side(outerShoulder, outerAngle), top(outerShoulder, outerAngle));
        segment.quadTo(-side(outerRadius, outerAngle), top(outerRadius, outerAngle),
                -side(outerRadius, outerCurve), top(outerRadius, outerCurve));
        segment.close();

        int bound = (int) Math.ceil(outerRadius) + 1;
        segmentHitArea.setPath(segment, new Region(-bound, -bound, bound, bound));
    }

    private static float degreesForGap(float gap, float radius) {
        return (float) Math.toDegrees(Math.asin(gap / radius));
    }

    private static float side(float radius, float angle) {
        return radius * (float) Math.sin(Math.toRadians(angle));
    }

    private static float top(float radius, float angle) {
        return -radius * (float) Math.cos(Math.toRadians(angle));
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            if (outerRadius > 0 && !revealed && !closing) prepareReveal();
        } else {
            removeCallbacks(fallback);
            if (!preview && !closing && isAttachedToWindow()) post(close);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!preview && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) dismiss();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void prepareReveal() {
        removeCallbacks(fallback);
        postDelayed(fallback, waitingForHover ? (requireHover ? 180 : 90) : 0);
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (closing) return true;
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            hoverPending = false;
            showBattery(false, true);
            highlight(-1);
        } else if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            if (!revealed && waitingForHover && hasWindowFocus() && outerRadius > 0 && PenInput.isPen(event, 0)) {
                waitingForHover = false;
                removeCallbacks(fallback);
                android.util.Log.d("PenDock", "Radial menu anchored at stylus hover");
                reveal(event.getX(), event.getY());
                if (revealed) updateHover(event.getX(), event.getY());
                return true;
            }
            if (revealed) updateHover(event.getX(), event.getY());
        }
        return super.dispatchHoverEvent(event);
    }

    private void updateHover(float x, float y) {
        if (opening) {
            hoverPending = true;
            hoverX = x;
            hoverY = y;
            return;
        }
        int selection = sectorAt(x, y);
        float dx = x - centerX;
        float dy = y - centerY;
        float hubRadius = dp(HUB_RADIUS_DP) * scale;
        showBattery(selection < 0 && dx * dx + dy * dy <= hubRadius * hubRadius, selection < 0);
        highlight(selection);
    }

    // Tap the whole segment, including the space around the icon.
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!penOnly || !PenInput.isTouchpad(event)) return super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) dismiss();
        return true;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!penOnly || !PenInput.isTouchpad(event)) return super.dispatchGenericMotionEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) dismiss();
        return true;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return event.getActionMasked() == MotionEvent.ACTION_DOWN;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (penOnly && action == MotionEvent.ACTION_DOWN
                && !PenInput.isPen(event, 0)) {
            dismiss();
            return true;
        }
        if (closing) return true;
        if (action == MotionEvent.ACTION_CANCEL) {
            pressedPointer = -1;
            pressed = -1;
            highlight(-1);
            return true;
        }
        int pointer = action == MotionEvent.ACTION_MOVE
                ? event.findPointerIndex(pressedPointer) : event.getActionIndex();
        if (pointer < 0 || (penOnly && !PenInput.isPen(event, pointer))) return true;

        int pointerId = event.getPointerId(pointer);
        int sector = sectorAt(event.getX(pointer), event.getY(pointer));
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pressedPointer = pointerId;
                hoverPending = false;
                showBattery(false, false);
                waitingForHover = false;
                pressed = sector;
                highlight(sector);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pressedPointer != -1) return true;
                pressedPointer = pointerId;
                hoverPending = false;
                showBattery(false, false);
                waitingForHover = false;
                pressed = sector;
                highlight(sector);
                return true;
            case MotionEvent.ACTION_MOVE:
                highlight(sector);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (pointerId != pressedPointer) return true;
                pressedPointer = -1;
                if (sector >= 0 && sector == pressed) choose(sector);
                else if (sector < 0 && pressed < 0) performClick();
                else highlight(-1);
                pressed = -1;
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        dismiss();
        return true;
    }

    private int sectorAt(float x, float y) {
        if (!revealed || ring.getAlpha() < .65f || buttons.length == 0) return -1;
        float dx = x - centerX, dy = y - centerY;
        if (!RingConfig.STYLE_PETALS.equals(style)) {
            float dist = (float) Math.hypot(dx, dy);
            float minR = dp(HUB_RADIUS_DP + 4) * scale;
            float maxR = outerRadius + dp(12) * scale;
            if (dist < minR || dist > maxR) return -1;
            int best = -1;
            float bestDot = -2f;
            float inv = 1f / Math.max(1f, dist);
            float nx = dx * inv;
            float ny = dy * inv;
            for (int index = 0; index < buttons.length; index++) {
                float dot = nx * directionX[index] + ny * directionY[index];
                if (dot > bestDot) {
                    bestDot = dot;
                    best = index;
                }
            }
            return best;
        }
        for (int index = 0; index < buttons.length; index++) {
            float across = -dx * directionY[index] + dy * directionX[index];
            float inward = -dx * directionX[index] - dy * directionY[index];
            if (segmentHitArea.contains(Math.round(across), Math.round(inward))
                    || segmentHitArea.contains(Math.round(across),
                            Math.round(inward + segmentOffset(index)))) return index;
        }
        return -1;
    }

    private float cellRadius() {
        float maxBySpacing = buttons.length <= 1 ? dp(30) * scale
                : (float) (iconOrbit * Math.sin(Math.PI / buttons.length)) - dp(3.5f) * scale;
        return Math.max(dp(22) * scale, Math.min(dp(30) * scale, maxBySpacing));
    }

    private void buildRoundedHexagon(Path out, float radius, float corner) {
        out.reset();
        if (radius <= 0f) return;
        float[] vx = new float[6];
        float[] vy = new float[6];
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(-90.0 + i * 60.0);
            vx[i] = (float) (Math.cos(a) * radius);
            vy[i] = (float) (Math.sin(a) * radius);
        }
        float c = Math.min(corner, radius * 0.36f);
        for (int i = 0; i < 6; i++) {
            int prev = (i + 5) % 6;
            int next = (i + 1) % 6;
            float dx1 = vx[prev] - vx[i], dy1 = vy[prev] - vy[i];
            float len1 = (float) Math.hypot(dx1, dy1);
            float dx2 = vx[next] - vx[i], dy2 = vy[next] - vy[i];
            float len2 = (float) Math.hypot(dx2, dy2);
            float sx = vx[i] + dx1 / len1 * c;
            float sy = vy[i] + dy1 / len1 * c;
            float ex = vx[i] + dx2 / len2 * c;
            float ey = vy[i] + dy2 / len2 * c;
            if (i == 0) out.moveTo(sx, sy);
            else out.lineTo(sx, sy);
            out.quadTo(vx[i], vy[i], ex, ey);
        }
        out.close();
    }

    private void revealAtCenter() {
        if (requireHover) {
            DiagLog.log("Menu", "Skipped menu: requireNearScreen=true & no stylus hover");
            dismiss();
            return;
        }
        waitingForHover = false;
        if (preview) {
            reveal(getWidth() / 2f, getHeight() / 2f);
        } else {
            getLocationOnScreen(screenOrigin);
            reveal(fallbackX - screenOrigin[0], fallbackY - screenOrigin[1]);
        }
    }

    private void reveal(float x, float y) {
        if (revealed || closing || !isAttachedToWindow() || (!preview && !hasWindowFocus())) return;
        place(x, y);
        revealed = true;
        boolean animate = !preview && ValueAnimator.areAnimatorsEnabled();
        if (!preview) animateDim(1f, animate ? OPEN_DURATION_MS : 0, OPEN);
        if (animate && startSurfaceAnimation(true, OPEN, OPEN_DURATION_MS, () -> {
            ring.setVisibility(VISIBLE);
            completeOpening();
        })) {
            opening = true;
            return;
        }
        if (animate) {
            opening = true;
            ring.setScaleX(.48f);
            ring.setScaleY(.48f);
            ring.setRotation(-32f);
            ring.setAlpha(0f);
        }
        ring.setVisibility(VISIBLE);
        if (animate) {
            ring.animate().scaleX(1f).scaleY(1f).rotation(0f).alpha(1f)
                    .setDuration(OPEN_DURATION_MS).setInterpolator(OPEN).withLayer()
                    .withEndAction(this::completeOpening).start();
        }
    }

    private void completeOpening() {
        opening = false;
        if (hoverPending && hasWindowFocus()) {
            hoverPending = false;
            updateHover(hoverX, hoverY);
        }
    }

    private boolean startSurfaceAnimation(boolean entering, TimeInterpolator easing, long duration, Runnable next) {
        if (!useSeparateSurface || ring.getWidth() != ringSize || ring.getHeight() != ringSize) return false;
        Bitmap artwork = Bitmap.createBitmap(ringSize, ringSize, Bitmap.Config.ARGB_8888);
        try {
            ring.draw(new Canvas(artwork));
        } catch (RuntimeException unsupported) {
            artwork.recycle();
            return false;
        }

        Consumer<SurfaceRingAnimation> complete = finished -> {
            if (surfaceAnimation == finished && stopSurfaceAnimation() && isAttachedToWindow()) next.run();
        };
        SurfaceRingAnimation animation = new SurfaceRingAnimation(getContext(), artwork, easing, duration,
                entering, this::handoffClosingSurface, complete);
        LayoutParams position = new LayoutParams(ringSize, ringSize);
        position.leftMargin = Math.round(ring.getTranslationX());
        position.topMargin = Math.round(ring.getTranslationY());
        surfaceAnimation = animation;
        surfaceTimeout = () -> complete.accept(animation);
        addView(animation, position);
        postDelayed(surfaceTimeout, duration + 260);
        return true;
    }

    private void handoffClosingSurface(Runnable begin) {
        if (!closing || surfaceAnimation == null || !isAttachedToWindow()) return;
        // Leave the live ring visible until the separate surface has submitted its first buffer.
        ring.setVisibility(INVISIBLE);
        if (isHardwareAccelerated()) getViewTreeObserver().registerFrameCommitCallback(begin);
        else begin.run();
    }

    private boolean stopSurfaceAnimation() {
        SurfaceRingAnimation animation = surfaceAnimation;
        if (animation == null) return false;
        removeCallbacks(surfaceTimeout);
        surfaceAnimation = null;
        surfaceTimeout = null;
        animation.stop();
        if (isAttachedToWindow()) removeView(animation);
        return true;
    }

    private void place(float x, float y) {
        anchorX = x;
        anchorY = y;
        float extent = outerRadius + highlightOffset + dp(14) * scale;
        centerX = Math.max(extent, Math.min(getWidth() - extent, x));
        centerY = Math.max(extent, Math.min(getHeight() - extent, y));
        ring.setTranslationX(centerX - ringSize / 2f);
        ring.setTranslationY(centerY - ringSize / 2f);
    }

    private void updateArtwork() {
        float middle = ringSize / 2f;
        for (int index = 0; index < buttons.length; index++) {
            View button = buttons[index];
            float selected = button.isEnabled() ? highlights[index] : 0f;
            float distance = iconOrbit + segmentOffset(index);
            button.setTranslationX(middle + directionX[index] * distance - iconHalf);
            button.setTranslationY(middle + directionY[index] * distance - iconHalf);
            float iconScale = scale * (1f + .08f * selected);
            button.setScaleX(iconScale);
            button.setScaleY(iconScale);
            if (tools[index] != null) {
                int color = button.isEnabled() ? blend(colors.text, colors.accent, selected) : colors.disabledInk;
                if (color != iconColors[index]) {
                    ((ImageView) button).setColorFilter(color);
                    iconColors[index] = color;
                }
            }
        }
        updateHub();
        ring.invalidate();
    }

    private float segmentOffset(int index) {
        return buttons[index].isEnabled() ? highlightOffset * scale * highlights[index] : 0f;
    }

    private void updateHub() {
        float middle = ringSize / 2f;
        float expansion = batteryExpansion;
        hub.setTranslationX(middle - hubHalfWidth);
        hub.setTranslationY(middle - dp(12) - dp(10) * scale * expansion);
        hub.setScaleX(scale);
        hub.setScaleY(scale);
        batteryStatus.setTranslationX(middle - hubHalfWidth);
        batteryStatus.setTranslationY(middle - dp(3) + dp(5) * scale * expansion);
        batteryStatus.setScaleX(scale * lerp(.48f, 1f, expansion));
        batteryStatus.setScaleY(scale * lerp(.48f, 1f, expansion));
        batteryStatus.setAlpha(expansion);
        batteryStatus.setVisibility(expansion > 0f ? VISIBLE : INVISIBLE);
    }

    private void showBattery(boolean show, boolean animate) {
        if (animate && batteryWanted == show) return;
        batteryWanted = show;
        if (batteryAnimation != null) batteryAnimation.cancel();
        float target = show ? 1f : 0f;
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            if (batteryExpansion != target) {
                batteryExpansion = target;
                updateHub();
            }
            return;
        }
        if (batteryExpansion == target) return;
        batteryAnimation = ValueAnimator.ofFloat(batteryExpansion, target);
        batteryAnimation.setDuration(show ? 180 : 145);
        batteryAnimation.setInterpolator(OPEN);
        batteryAnimation.addUpdateListener(animation -> {
            batteryExpansion = (float) animation.getAnimatedValue();
            updateHub();
        });
        batteryAnimation.start();
    }

    // Only the small artwork pane is transformed during open and close.
    private final class Ring extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcBounds = new RectF();
        private final RectF innerArcBounds = new RectF();
        private final Path slicePath = new Path();
        private final Path pointerPath = new Path();

        Ring(Context context) {
            super(context);
            setWillNotDraw(false);
            shadowPaint.setStyle(Paint.Style.FILL);
        }

        // Fade the small ring directly without allocating an intermediate alpha layer.
        @Override public boolean hasOverlappingRendering() { return false; }

        @Override protected void onDraw(Canvas canvas) {
            float middle = ringSize / 2f;
            float hubRadius = dp(HUB_RADIUS_DP) * scale;
            float shadowRadius = Math.max(1f, dp(9.5f) * scale);

            if (RingConfig.STYLE_ORBIT.equals(style)) {
                drawOrbit(canvas, middle, hubRadius, shadowRadius);
            } else if (RingConfig.STYLE_HALO.equals(style)) {
                drawGlassHalo(canvas, middle, hubRadius, shadowRadius);
            } else if (RingConfig.STYLE_HEXA.equals(style)) {
                drawHexa(canvas, middle, shadowRadius);
            } else if (RingConfig.STYLE_DIAL.equals(style)) {
                drawDial(canvas, middle, hubRadius, shadowRadius);
            } else if (RingConfig.STYLE_MINIMAL.equals(style)) {
                drawMinimal(canvas, middle, hubRadius, shadowRadius);
            } else {
                drawPetals(canvas, middle, hubRadius, shadowRadius);
            }
        }

        private void drawPetals(Canvas canvas, float middle, float hubRadius, float shadowRadius) {
            // Pass 1: cast soft shadows behind both hub and petals so shadows never muddy adjacent petals.
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(colors.core);
            shadowPaint.setShadowLayer(shadowRadius, 0f, 0f, colors.ringShadow);
            canvas.drawCircle(middle, middle, hubRadius, shadowPaint);
            int shadowSave = canvas.save();
            canvas.translate(middle, middle);
            for (int index = 0; index < buttons.length; index++) {
                int segmentSave = canvas.save();
                canvas.rotate(angles[index] + 90);
                canvas.translate(0, -segmentOffset(index));
                canvas.drawPath(segment, shadowPaint);
                canvas.restoreToCount(segmentSave);
            }
            canvas.restoreToCount(shadowSave);

            // Pass 2: crisp fills and subtle contrast borders on top.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors.core);
            canvas.drawCircle(middle, middle, hubRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, dp(1) * .85f * scale));
            paint.setColor(colors.ringBorder);
            canvas.drawCircle(middle, middle, hubRadius, paint);
            int ringSave = canvas.save();
            canvas.translate(middle, middle);
            for (int index = 0; index < buttons.length; index++) {
                float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                int segmentSave = canvas.save();
                canvas.rotate(angles[index] + 90);
                canvas.translate(0, -segmentOffset(index));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(buttons[index].isEnabled() ? blend(colors.petal, colors.petalSelected, selected)
                        : colors.petalDisabled);
                canvas.drawPath(segment, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(selected > 0.01f
                        ? blendArgb(colors.ringBorder, withAlpha(colors.accent, 210), selected)
                        : colors.ringBorder);
                canvas.drawPath(segment, paint);
                canvas.restoreToCount(segmentSave);
            }
            canvas.restoreToCount(ringSave);
        }

        private void drawOrbit(Canvas canvas, float middle, float hubRadius, float shadowRadius) {
            float baseRadius = cellRadius();
            // Delicate orbital guide connecting all floating bubbles.
            if (buttons.length > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(1.5f) * scale));
                paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 110 : 75));
                canvas.drawCircle(middle, middle, iconOrbit, paint);
            }

            // Pass 1: soft shadows behind hub and bubbles.
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(colors.core);
            shadowPaint.setShadowLayer(shadowRadius, 0f, 0f, colors.ringShadow);
            canvas.drawCircle(middle, middle, hubRadius, shadowPaint);
            for (int index = 0; index < buttons.length; index++) {
                float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                float dist = iconOrbit + segmentOffset(index);
                float bx = middle + directionX[index] * dist;
                float by = middle + directionY[index] * dist;
                float r = baseRadius + dp(2.4f) * scale * selected;
                canvas.drawCircle(bx, by, r, shadowPaint);
            }

            // Pass 2: crisp circular hub + orbiting bubbles.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors.core);
            canvas.drawCircle(middle, middle, hubRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(1f) * scale));
            paint.setColor(colors.ringBorder);
            canvas.drawCircle(middle, middle, hubRadius, paint);

            for (int index = 0; index < buttons.length; index++) {
                float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                float dist = iconOrbit + segmentOffset(index);
                float bx = middle + directionX[index] * dist;
                float by = middle + directionY[index] * dist;
                float r = baseRadius + dp(2.4f) * scale * selected;

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(buttons[index].isEnabled()
                        ? blend(colors.petal, colors.petalSelected, selected) : colors.petalDisabled);
                canvas.drawCircle(bx, by, r, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(lerp(0.9f, 1.8f, selected)) * scale));
                paint.setColor(selected > 0.01f
                        ? blendArgb(colors.ringBorder, withAlpha(colors.accent, 220), selected)
                        : colors.ringBorder);
                canvas.drawCircle(bx, by, r, paint);
            }
        }

        private void buildAnnularSlice(Path out, float cx, float cy, float rIn, float rOut,
                float startDeg, float sweepDeg) {
            out.reset();
            if (sweepDeg >= 359.5f) {
                out.addCircle(cx, cy, rOut, Path.Direction.CW);
                out.addCircle(cx, cy, rIn, Path.Direction.CCW);
                return;
            }
            arcBounds.set(cx - rOut, cy - rOut, cx + rOut, cy + rOut);
            innerArcBounds.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn);
            out.arcTo(arcBounds, startDeg, sweepDeg, true);
            out.arcTo(innerArcBounds, startDeg + sweepDeg, -sweepDeg, false);
            out.close();
        }

        private void drawGlassHalo(Canvas canvas, float middle, float hubRadius, float shadowRadius) {
            float innerR = dp(60) * scale;
            float outerR = outerRadius;
            float trackThickness = Math.max(dp(24) * scale, outerR - innerR);
            float midR = (innerR + outerR) * 0.5f;

            // True translucent frosted glass: avoid opaque shadow fills behind the glass body.
            if (buttons.length > 0) {
                // Soft outer ambient edge drop without muddying the translucent interior.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeWidth(dp(4.5f) * scale);
                paint.setColor(withAlpha(0xff000000, colors.dark ? 45 : 22));
                canvas.drawCircle(middle, middle, outerR + dp(1.8f) * scale, paint);

                // Translucent frosted glass donut fill (~45% opacity so background shines through).
                paint.setStrokeWidth(trackThickness);
                paint.setColor(colors.glassFill);
                canvas.drawCircle(middle, middle, midR, paint);

                // Subtle top-to-bottom glass specular sheen for real liquid-glass depth.
                Shader glassSheen = new android.graphics.LinearGradient(
                        middle, middle - outerR, middle, middle + outerR,
                        colors.dark ? new int[] {0x32ffffff, 0x06ffffff, colors.glassSheenAccent}
                                : new int[] {0x60ffffff, 0x0cffffff, 0x30ffffff},
                        new float[] {0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
                paint.setShader(glassSheen);
                canvas.drawCircle(middle, middle, midR, paint);
                paint.setShader(null);

                // Strictly bounded translucent glass highlight slice on hover (zero overlap).
                float step = 360f / buttons.length;
                float padDeg = buttons.length <= 1 ? 0f : Math.min(3.0f, step * 0.08f);
                float sliceIn = innerR + dp(3.5f) * scale;
                float sliceOut = outerR - dp(3.5f) * scale;
                float rimR = outerR - dp(1.5f) * scale;
                for (int index = 0; index < buttons.length; index++) {
                    float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                    if (selected <= 0.01f) continue;
                    float sweep = Math.max(6f, step - padDeg * 2f);
                    float startDeg = angles[index] - sweep * 0.5f;

                    buildAnnularSlice(slicePath, middle, middle, sliceIn, sliceOut, startDeg, sweep);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(withAlpha(colors.glassSlice, Math.round((colors.dark ? 96f : 152f) * selected)));
                    canvas.drawPath(slicePath, paint);

                    // Crisp outer rim accent indicator arc within the exact same slice.
                    arcBounds.set(middle - rimR, middle - rimR, middle + rimR, middle + rimR);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(Math.max(2f, dp(2.4f) * scale));
                    paint.setColor(withAlpha(colors.accent, Math.round(230 * selected)));
                    canvas.drawArc(arcBounds, startDeg + 2.5f, Math.max(4f, sweep - 5f), false, paint);
                }

                // Luminous specular glass rims (bright inner bevel + delicate contrast outline).
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeWidth(Math.max(1.2f, dp(1.3f) * scale));
                paint.setColor(colors.dark ? 0x52ffffff : 0xd8ffffff);
                canvas.drawCircle(middle, middle, outerR - dp(0.8f) * scale, paint);
                canvas.drawCircle(middle, middle, innerR + dp(0.8f) * scale, paint);
                paint.setStrokeWidth(Math.max(1f, dp(0.95f) * scale));
                paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 110 : 72));
                canvas.drawCircle(middle, middle, outerR + dp(0.3f) * scale, paint);
                canvas.drawCircle(middle, middle, innerR - dp(0.3f) * scale, paint);
            }

            // Matching frosted translucent center hub (legible yet airy).
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors.glassHub);
            canvas.drawCircle(middle, middle, hubRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.1f, dp(1.2f) * scale));
            paint.setColor(colors.dark ? 0x58ffffff : 0xd8ffffff);
            canvas.drawCircle(middle, middle, hubRadius - dp(0.7f) * scale, paint);
            paint.setStrokeWidth(Math.max(1f, dp(0.95f) * scale));
            paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 110 : 72));
            canvas.drawCircle(middle, middle, hubRadius + dp(0.3f) * scale, paint);
        }

        private void drawHexa(Canvas canvas, float middle, float shadowRadius) {
            // Pass 1: soft shadows behind hex hub and hex cells.
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(colors.core);
            shadowPaint.setShadowLayer(shadowRadius, 0f, 0f, colors.ringShadow);
            int shadowSave = canvas.save();
            canvas.translate(middle, middle);
            canvas.drawPath(hubHex, shadowPaint);
            for (int index = 0; index < buttons.length; index++) {
                float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                float dist = iconOrbit + segmentOffset(index);
                int cellSave = canvas.save();
                canvas.translate(directionX[index] * dist, directionY[index] * dist);
                canvas.rotate(angles[index] + 90f);
                float cellScale = 1f + 0.06f * selected;
                canvas.scale(cellScale, cellScale);
                canvas.drawPath(cellHex, shadowPaint);
                canvas.restoreToCount(cellSave);
            }
            canvas.restoreToCount(shadowSave);

            // Pass 2: crisp architectural hex fills + beveled borders.
            int ringSave = canvas.save();
            canvas.translate(middle, middle);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors.core);
            canvas.drawPath(hubHex, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(1.1f) * scale));
            paint.setColor(colors.ringBorder);
            canvas.drawPath(hubHex, paint);
            // Subtle inner hex chamfer inside hub.
            int innerSave = canvas.save();
            canvas.scale(0.87f, 0.87f);
            paint.setStrokeWidth(Math.max(1f, dp(0.75f) * scale));
            paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 70 : 35));
            canvas.drawPath(hubHex, paint);
            canvas.restoreToCount(innerSave);

            for (int index = 0; index < buttons.length; index++) {
                float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                float dist = iconOrbit + segmentOffset(index);
                int cellSave = canvas.save();
                canvas.translate(directionX[index] * dist, directionY[index] * dist);
                canvas.rotate(angles[index] + 90f);
                float cellScale = 1f + 0.06f * selected;
                canvas.scale(cellScale, cellScale);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(buttons[index].isEnabled()
                        ? blend(colors.petal, colors.petalSelected, selected) : colors.petalDisabled);
                canvas.drawPath(cellHex, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(lerp(1.0f, 1.8f, selected)) * scale));
                paint.setColor(selected > 0.01f
                        ? blendArgb(colors.ringBorder, withAlpha(colors.accent, 230), selected)
                        : colors.ringBorder);
                canvas.drawPath(cellHex, paint);
                canvas.restoreToCount(cellSave);
            }
            canvas.restoreToCount(ringSave);
        }

        private void drawDial(Canvas canvas, float middle, float hubRadius, float shadowRadius) {
            float bezelOut = outerRadius + dp(2) * scale;
            float bezelIn = outerRadius - dp(7) * scale;
            float dialIn = hubRadius + dp(6) * scale;
            float dialMid = (dialIn + bezelIn) * 0.5f;
            float dialWidth = Math.max(dp(20) * scale, bezelIn - dialIn);

            // Pass 1: cast one crisp unified shadow behind the full precision watch-dial disc.
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(colors.core);
            shadowPaint.setShadowLayer(shadowRadius, 0f, 0f, colors.ringShadow);
            canvas.drawCircle(middle, middle, buttons.length > 0 ? bezelOut : hubRadius, shadowPaint);

            if (buttons.length > 0) {
                // Outer instrument bezel backing + main dial face.
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(colors.core);
                canvas.drawCircle(middle, middle, bezelOut, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeWidth(dialWidth);
                paint.setColor(colors.petal);
                canvas.drawCircle(middle, middle, dialMid, paint);

                // Clean bounded sector spotlight when hovered.
                float step = 360f / buttons.length;
                float halfStep = step * 0.5f;
                for (int index = 0; index < buttons.length; index++) {
                    float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                    if (selected <= 0.01f) continue;
                    float startDeg = angles[index] - halfStep;
                    buildAnnularSlice(slicePath, middle, middle,
                            dialIn + dp(1) * scale, bezelIn - dp(1) * scale, startDeg, step);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(withAlpha(colors.petalSelected, Math.round(240 * selected)));
                    canvas.drawPath(slicePath, paint);
                }

                // Thin radial sector hairlines separating items on the dial face.
                if (buttons.length > 1) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, dp(0.85f) * scale));
                    paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 115 : 65));
                    for (int index = 0; index < buttons.length; index++) {
                        double rad = Math.toRadians(angles[index] - halfStep);
                        float cos = (float) Math.cos(rad);
                        float sin = (float) Math.sin(rad);
                        canvas.drawLine(middle + cos * (dialIn + dp(3) * scale),
                                middle + sin * (dialIn + dp(3) * scale),
                                middle + cos * (bezelIn - dp(2) * scale),
                                middle + sin * (bezelIn - dp(2) * scale), paint);
                    }
                }

                // 36 precision watch-bezel tick marks around the outer rim; hovered sector ticks glow accent.
                int tickCount = 36;
                float tickStep = 360f / tickCount;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                for (int t = 0; t < tickCount; t++) {
                    float deg = -90f + t * tickStep;
                    float active = 0f;
                    for (int index = 0; index < buttons.length; index++) {
                        float sel = buttons[index].isEnabled() ? highlights[index] : 0f;
                        if (sel <= 0.01f) continue;
                        float diff = Math.abs(wrapDegrees(deg - angles[index]));
                        if (diff <= halfStep - 1.5f) {
                            active = Math.max(active, sel);
                        }
                    }
                    boolean major = (t % 3 == 0);
                    float len = dp(major ? 4.8f : 3.0f) * scale;
                    float r1 = bezelOut - dp(2.0f) * scale;
                    float r0 = r1 - len;
                    double rad = Math.toRadians(deg);
                    float cos = (float) Math.cos(rad);
                    float sin = (float) Math.sin(rad);
                    paint.setStrokeWidth(Math.max(1f, dp(major ? 1.5f : 0.95f) * scale));
                    int restTick = withAlpha(colors.muted, colors.dark ? 145 : 115);
                    paint.setColor(active > 0.01f
                            ? blendArgb(restTick, withAlpha(colors.accent, 245), active)
                            : restTick);
                    canvas.drawLine(middle + cos * r0, middle + sin * r0,
                            middle + cos * r1, middle + sin * r1, paint);
                }

                // Subtle bezel & inner track rings.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStrokeWidth(Math.max(1f, dp(1.0f) * scale));
                paint.setColor(colors.ringBorder);
                canvas.drawCircle(middle, middle, bezelOut, paint);
                paint.setStrokeWidth(Math.max(1f, dp(0.8f) * scale));
                paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 120 : 70));
                canvas.drawCircle(middle, middle, bezelIn, paint);
                canvas.drawCircle(middle, middle, dialIn, paint);
            }

            // Center watch-crown hub + precision triangular compass pointer toward hovered item.
            for (int index = 0; index < buttons.length; index++) {
                float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                if (selected <= 0.01f) continue;
                int ptrSave = canvas.save();
                canvas.translate(middle, middle);
                canvas.rotate(angles[index] + 90f);
                float tip = hubRadius + dp(7.5f) * scale * selected;
                float base = hubRadius - dp(1.5f) * scale;
                float halfW = dp(5.5f) * scale;
                pointerPath.reset();
                pointerPath.moveTo(0f, -tip);
                pointerPath.lineTo(-halfW, -base);
                pointerPath.lineTo(halfW, -base);
                pointerPath.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(colors.accent, Math.round(240 * selected)));
                canvas.drawPath(pointerPath, paint);
                canvas.restoreToCount(ptrSave);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors.core);
            canvas.drawCircle(middle, middle, hubRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(1.1f) * scale));
            paint.setColor(colors.ringBorder);
            canvas.drawCircle(middle, middle, hubRadius, paint);
            paint.setStrokeWidth(Math.max(1f, dp(0.75f) * scale));
            paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 90 : 45));
            canvas.drawCircle(middle, middle, hubRadius - dp(3.5f) * scale, paint);
        }

        private float wrapDegrees(float deg) {
            float wrapped = deg % 360f;
            if (wrapped > 180f) wrapped -= 360f;
            if (wrapped < -180f) wrapped += 360f;
            return wrapped;
        }

        private void drawMinimal(Canvas canvas, float middle, float hubRadius, float shadowRadius) {
            float badgeR = cellRadius() * 0.80f;
            float maxHoveredOuter = iconOrbit + highlightOffset * scale + badgeR + dp(2.0f) * scale;
            float haloR = Math.min(middle - dp(5f) * scale, maxHoveredOuter + dp(6.5f) * scale);
            float sleekHubR = hubRadius * 0.94f;

            // Whisper-thin outer halo ring with clean breathing room outside every icon badge.
            if (buttons.length > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(1f) * scale));
                paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 115 : 70));
                canvas.drawCircle(middle, middle, haloR, paint);
            }

            // Soft hub shadow + translucent hub.
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(withAlpha(colors.core, colors.dark ? 215 : 228));
            shadowPaint.setShadowLayer(shadowRadius * 0.85f, 0f, 0f, colors.ringShadow);
            canvas.drawCircle(middle, middle, sleekHubR, shadowPaint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(colors.core, colors.dark ? 218 : 230));
            canvas.drawCircle(middle, middle, sleekHubR, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(0.9f) * scale));
            paint.setColor(withAlpha(colors.ringBorder, colors.dark ? 130 : 80));
            canvas.drawCircle(middle, middle, sleekHubR, paint);

            // Airy translucent icon badges + crisp glowing outer indicator arc on hover.
            float step = buttons.length > 0 ? 360f / buttons.length : 360f;
            float arcSweep = Math.min(34f, Math.max(14f, step * 0.55f));
            arcBounds.set(middle - haloR, middle - haloR, middle + haloR, middle + haloR);
            for (int index = 0; index < buttons.length; index++) {
                float selected = buttons[index].isEnabled() ? highlights[index] : 0f;
                float dist = iconOrbit + segmentOffset(index);
                float bx = middle + directionX[index] * dist;
                float by = middle + directionY[index] * dist;
                float r = badgeR + dp(2.0f) * scale * selected;

                paint.setStyle(Paint.Style.FILL);
                int restAlpha = colors.dark ? 165 : 180;
                int activeAlpha = 242;
                int fill = buttons[index].isEnabled()
                        ? withAlpha(blend(colors.petal, colors.petalSelected, selected),
                                Math.round(lerp(restAlpha, activeAlpha, selected)))
                        : withAlpha(colors.petalDisabled, colors.dark ? 120 : 140);
                paint.setColor(fill);
                canvas.drawCircle(bx, by, r, paint);

                if (selected > 0.01f) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, dp(1.5f) * scale));
                    paint.setColor(withAlpha(colors.accent, Math.round(215 * selected)));
                    canvas.drawCircle(bx, by, r, paint);

                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(Math.max(2f, dp(2.8f) * scale));
                    paint.setColor(withAlpha(colors.accent, Math.round(235 * selected)));
                    canvas.drawArc(arcBounds, angles[index] - arcSweep * 0.5f, arcSweep, false, paint);
                    paint.setStrokeCap(Paint.Cap.BUTT);
                }
            }
        }
    }

    private void highlight(int selection) {
        if (hovered == selection) return;
        hovered = selection;
        hub.setText(selection < 0 ? "PenDock" : labels[selection]);
        if (highlightAnimation != null) highlightAnimation.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            for (int index = 0; index < highlights.length; index++) highlights[index] = index == selection ? 1f : 0f;
            updateArtwork();
            return;
        }
        float[] start = highlights.clone();
        highlightAnimation = ValueAnimator.ofFloat(0f, 1f);
        highlightAnimation.setDuration(135);
        highlightAnimation.setInterpolator(new LinearInterpolator());
        highlightAnimation.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            for (int index = 0; index < highlights.length; index++) {
                highlights[index] = lerp(start[index], index == selection ? 1f : 0f, t);
            }
            updateArtwork();
        });
        highlightAnimation.start();
    }

    private void choose(int index) {
        if (preview) {
            highlight(index);
            return;
        }
        Runnable action = actions[index];
        if (action != null) finish(action,
                tools[index] == Shortcuts.Tool.FLOAT_WINDOW || tools[index] == Shortcuts.Tool.SPLIT_SCREEN ? 110 : 175);
    }

    void dismiss() {
        if (preview) highlight(-1);
        else finish(close, 175);
    }

    private void finish(Runnable next, long duration) {
        if (closing) return;
        closing = true;
        if (stopSurfaceAnimation()) ring.setVisibility(VISIBLE);
        opening = false;
        hoverPending = false;
        boolean[] completed = {false};
        Runnable done = () -> {
            if (completed[0]) return;
            completed[0] = true;
            next.run();
        };
        if (!revealed || !ValueAnimator.areAnimatorsEnabled()) {
            if (dimAnimation != null) dimAnimation.cancel();
            dimProgress = 0f;
            applyDim();
            done.run();
            return;
        }
        animateDim(0f, duration, CLOSE);
        ring.animate().cancel();
        if (highlightAnimation != null) highlightAnimation.cancel();
        if (batteryAnimation != null) batteryAnimation.cancel();
        postDelayed(done, duration + 260);
        if (startSurfaceAnimation(false, CLOSE, duration, done)) return;
        ring.animate().scaleX(.48f).scaleY(.48f).rotation(-32f).alpha(0f)
                .setDuration(duration).setInterpolator(CLOSE).withLayer().withEndAction(done).start();
    }

    @Override protected void onDetachedFromWindow() {
        if (surfaceAnimation != null) {
            removeCallbacks(surfaceTimeout);
            surfaceAnimation.stop();
            surfaceAnimation = null;
            surfaceTimeout = null;
        }
        removeCallbacks(fallback);
        removeCallbacks(close);
        ring.animate().cancel();
        if (highlightAnimation != null) highlightAnimation.cancel();
        if (batteryAnimation != null) batteryAnimation.cancel();
        if (dimAnimation != null) dimAnimation.cancel();
        if (blurFrame != null) {
            blurFrame.recycle();
            blurFrame = null;
        }
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Ui.dp(getContext(), value); }

    private static float lerp(float from, float to, float t) { return from + (to - from) * t; }

    private static int withAlpha(int color, int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        return (color & 0x00ffffff) | (clamped << 24);
    }

    private static int blendArgb(int from, int to, float t) {
        return (Math.round(lerp((from >>> 24) & 255, (to >>> 24) & 255, t)) << 24)
                | (Math.round(lerp((from >> 16) & 255, (to >> 16) & 255, t)) << 16)
                | (Math.round(lerp((from >> 8) & 255, (to >> 8) & 255, t)) << 8)
                | Math.round(lerp(from & 255, to & 255, t));
    }

    private static int blend(int from, int to, float t) {
        return 0xff000000
                | Math.round(lerp((from >> 16) & 255, (to >> 16) & 255, t)) << 16
                | Math.round(lerp((from >> 8) & 255, (to >> 8) & 255, t)) << 8
                | Math.round(lerp(from & 255, to & 255, t));
    }
}
