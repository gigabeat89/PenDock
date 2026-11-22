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
    private static final int OPEN_DURATION_MS = 290;

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
    private final Region segmentHitArea = new Region();
    private final float fallbackX, fallbackY;
    private final TextView hub, batteryStatus;
    private final Runnable close;
    private final boolean penOnly, preview, requireHover, useSeparateSurface;
    private final float maxScale;
    private final Appearance.Palette colors;
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
            Runnable lock, Runnable capture, Runnable circleSearch, Runnable laserMode, Runnable close,
            List<Shortcuts.Entry> entries, Consumer<Shortcuts.Entry> launch, String battery,
            Runnable floatActive, boolean alreadyWindowed, Runnable splitActive, boolean alreadySplit,
            Bitmap blurFrame) {
        this(context, fallbackX, fallbackY, useHover, lock, capture, circleSearch, laserMode, close,
                entries, launch, battery, floatActive, alreadyWindowed, splitActive, alreadySplit,
                blurFrame, false);
    }

    static RadialMenu preview(Context context, List<Shortcuts.Entry> entries) {
        Runnable sample = () -> { };
        return new RadialMenu(context, 0, 0, false, sample, sample, sample, sample, sample,
                entries, app -> { }, "—", sample, false, sample, false, null, true);
    }

    private RadialMenu(Context context, float fallbackX, float fallbackY, boolean useHover,
            Runnable lock, Runnable capture, Runnable circleSearch, Runnable laserMode, Runnable close,
            List<Shortcuts.Entry> entries, Consumer<Shortcuts.Entry> launch, String battery,
            Runnable floatActive, boolean alreadyWindowed, Runnable splitActive, boolean alreadySplit,
            Bitmap blurFrame, boolean preview) {
        super(context);
        this.preview = preview;
        // Huawei can slow the overlay's UI frame cadence when no PenDock Accessibility service is bound.
        useSeparateSurface = !preview && "huawei".equalsIgnoreCase(Build.MANUFACTURER)
                && !WindowModeMonitor.isReady() && !SystemScreenshot.isReady() && !CircleCaptureService.isReady();
        maxScale = RingConfig.ringScale(context);
        dimAlpha = RingConfig.ringDimAlpha(context);
        colors = Ui.colors(context);
        this.fallbackX = fallbackX;
        this.fallbackY = fallbackY;
        this.close = close;
        penOnly = !preview && PenInput.isPenOnlyMenu(context);
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
            blurLayer = new View(context) {
                @Override protected void onDraw(Canvas canvas) {
                    Bitmap frame = RadialMenu.this.blurFrame;
                    if (frame == null || frame.isRecycled()) return;
                    getLocationOnScreen(screenOrigin);
                    canvas.drawBitmap(frame, -screenOrigin[0], -screenOrigin[1], framePaint);
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
        updateArtwork();
        if (revealed) place(anchorX, anchorY);
        else if (hasWindowFocus()) prepareReveal();
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
        postDelayed(fallback, waitingForHover ? 270 : 0);
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
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
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
        if (!revealed || ring.getAlpha() < .65f) return -1;
        float dx = x - centerX, dy = y - centerY;
        for (int index = 0; index < buttons.length; index++) {
            float across = -dx * directionY[index] + dy * directionX[index];
            float inward = -dx * directionX[index] - dy * directionY[index];
            if (segmentHitArea.contains(Math.round(across), Math.round(inward))
                    || segmentHitArea.contains(Math.round(across),
                            Math.round(inward + segmentOffset(index)))) return index;
        }
        return -1;
    }

    private void revealAtCenter() {
        if (requireHover) {
            android.util.Log.d("PenDock", "Skipped pen menu without stylus hover");
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
        if (revealed || closing || !isAttachedToWindow() || !hasWindowFocus()) return;
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

            // Pass 1: cast soft shadows behind both hub and petals so shadows never muddy adjacent petals.
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
                paint.setColor(colors.ringBorder);
                canvas.drawPath(segment, paint);
                canvas.restoreToCount(segmentSave);
            }
            canvas.restoreToCount(ringSave);
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
        if (!revealed || !ValueAnimator.areAnimatorsEnabled()) {
            if (dimAnimation != null) dimAnimation.cancel();
            dimProgress = 0f;
            applyDim();
            next.run();
            return;
        }
        animateDim(0f, duration, CLOSE);
        ring.animate().cancel();
        if (highlightAnimation != null) highlightAnimation.cancel();
        if (batteryAnimation != null) batteryAnimation.cancel();
        if (startSurfaceAnimation(false, CLOSE, duration, next)) return;
        ring.animate().scaleX(.48f).scaleY(.48f).rotation(-32f).alpha(0f)
                .setDuration(duration).setInterpolator(CLOSE).withLayer().withEndAction(next).start();
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

    private static int blend(int from, int to, float t) {
        return 0xff000000
                | Math.round(lerp((from >> 16) & 255, (to >> 16) & 255, t)) << 16
                | Math.round(lerp((from >> 8) & 255, (to >> 8) & 255, t)) << 8
                | Math.round(lerp(from & 255, to & 255, t));
    }
}
