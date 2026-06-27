package com.xyz.animator;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * LinearSlideAnimator animates one or more Android Views by sliding them in or out
 * of their parent's visible bounds along a chosen axis (left, right, up, or down).
 *
 * <p>Each target view is translated off-screen before an *_IN animation starts,
 * then animated back to its natural position. For *_OUT animations the reverse
 * applies: the view starts at its natural position and is translated off-screen.
 *
 * <p>Multiple views can be supplied; they animate with a configurable stagger delay
 * controlled by {@link #setReactionFactor(float)}. Hardware layers are enabled for
 * the duration of each animation and restored afterward to avoid memory overhead.
 *
 * <p>WeakReferences are used throughout to prevent memory leaks when the hosting
 * Activity or Fragment is destroyed while an animation is in flight.
 *
 * <p>Basic usage:
 * <pre>{@code
 * new LinearSlideAnimator(parentView, Direction.LEFT_IN)
 *     .setDuration(400)
 *     .withAlpha()
 *     .autoVisibility()
 *     .animate();
 * }</pre>
 */
public class LinearSlideAnimator {

    /** Weak reference to the parent view whose bounds define the off-screen offset. */
    private final WeakReference<View> parentViewRef;

    /** Weak references to every view that will be animated. */
    private final List<WeakReference<View>> targetViewsRefs = new ArrayList<>();

    /** All ValueAnimators currently in progress, used for cancellation and completion tracking. */
    private final List<ValueAnimator> runningAnimators = new ArrayList<>();

    /** External listeners notified on start, end, and cancel events. */
    private final List<AnimatorListener> listeners = new ArrayList<>();

    /** Total duration of a single view's animation in milliseconds. */
    private long duration = 400L;

    /** Delay applied before the first view begins animating, in milliseconds. */
    private long startDelay = 0L;

    /** The slide direction — one of the eight {@link Direction} values. */
    private Direction direction;

    /**
     * Optional override interpolator. When null, a {@link DecelerateInterpolator} is used
     * for *_IN directions and an {@link AccelerateInterpolator} for *_OUT directions.
     */
    private TimeInterpolator customInterpolator = null;

    /** Screen-coordinate bounding box of the parent view, captured just before animation starts. */
    private Rect parentRect;

    /**
     * Controls the stagger between consecutive views.
     * Each subsequent view starts at: {@code startDelay + duration * step * reactionFactor}.
     * A value of 0 means all views animate simultaneously; 1.0 means each view waits
     * one full {@code duration} before starting.
     */
    private float reactionFactor = 0.2f;

    /** When true the next {@link #animate()} call runs the animation in reverse order/direction. */
    private boolean withReverse = false;

    /** When true, alpha is cross-faded from 0→1 (in) or 1→0 (out) in sync with the translation. */
    private boolean withAlpha = false;

    /**
     * When true, *_IN targets are hidden (GONE or INVISIBLE) before animating and made VISIBLE
     * at the start of the animation; *_OUT targets are set to GONE when the animation ends.
     */
    private boolean autoVisibility = false;

    /** Registered with the parent so the pipeline is torn down if the view detaches. */
    private View.OnAttachStateChangeListener stateChangeListener;

    /**
     * Registered with the ViewTreeObserver when the parent has no measurable size yet.
     * Removed as soon as valid dimensions become available.
     */
    private ViewTreeObserver.OnGlobalLayoutListener dynamicLayoutObserver;

    /**
     * View tag key used to persist each view's original translationX/Y so it can be
     * restored correctly after an *_OUT animation or a cancelled run.
     *
     * <p>Uses a value far outside the aapt resource ID range to avoid collisions
     * with any caller-set tags. If your app uses integer tag keys near Integer.MAX_VALUE,
     * call {@link #setTagKeys(int, int)} with your own safe pair before constructing.
     */
    private static int TAG_TRANSLATION_KEY = Integer.MAX_VALUE;

    /**
     * View tag key used to persist each view's original layer type so hardware acceleration
     * can be reverted to exactly what it was before the animation started.
     */
    private static int TAG_LAYER_TYPE_KEY = Integer.MAX_VALUE - 1;

    /**
     * Overrides the default View tag keys used to store per-view animation state.
     *
     * <p>Call this once — before constructing any {@code LinearSlideAnimator} instance —
     * if your application already uses integer tag keys that clash with the defaults
     * ({@code 0x00FFFF01} and {@code 0x00FFFF02}). The keys must be distinct.
     *
     * @param translationKey Tag key for caching original translationX/Y.
     * @param layerTypeKey   Tag key for caching original layer type.
     * @throws IllegalArgumentException if the two keys are equal.
     */
    public static void setTagKeys(int translationKey, int layerTypeKey) {
        if (translationKey == layerTypeKey) {
            throw new IllegalArgumentException("translationKey and layerTypeKey must be distinct.");
        }
        TAG_TRANSLATION_KEY = translationKey;
        TAG_LAYER_TYPE_KEY = layerTypeKey;
    }

    /**
     * Callback interface for animation lifecycle events.
     */
    public interface AnimatorListener {
        void onAnimationStart(LinearSlideAnimator animator);
        void onAnimationEnd(LinearSlideAnimator animator);
        void onAnimationCancel(LinearSlideAnimator animator);
    }

    /**
     * The eight supported slide directions.
     * *_IN directions bring a view into the visible area; *_OUT directions remove it.
     */
    public enum Direction {
        LEFT_IN,
        LEFT_OUT,
        UP_IN,
        UP_OUT,
        RIGHT_IN,
        RIGHT_OUT,
        DOWN_IN,
        DOWN_OUT
    }

    /**
     * Convenience constructor that automatically collects every direct child of
     * {@code parentView} as a target when it is a {@link ViewGroup}.
     *
     * @param parentView The container whose bounds define off-screen offsets.
     * @param direction  The slide direction for the animation.
     */
    public LinearSlideAnimator(View parentView, Direction direction) {
        this.parentViewRef = new WeakReference<>(parentView);
        this.direction = direction;
        if (parentView instanceof ViewGroup) {
            for (View v : extractViews((ViewGroup) parentView)) {
                this.targetViewsRefs.add(new WeakReference<>(v));
            }
        }
        initLifecycleGuard();
    }

    /**
     * Constructor for animating an explicit set of views that may or may not be
     * direct children of {@code parentView}.
     *
     * @param parentView The container whose bounds define off-screen offsets.
     * @param direction  The slide direction for the animation.
     * @param views      One or more views to animate; null entries are silently skipped.
     */
    public LinearSlideAnimator(View parentView, Direction direction, View... views) {
        this.parentViewRef = new WeakReference<>(parentView);
        this.direction = direction;
        for (View v : views) {
            if (v != null) {
                this.targetViewsRefs.add(new WeakReference<>(v));
            }
        }
        initLifecycleGuard();
    }

    /**
     * Attaches a {@link View.OnAttachStateChangeListener} to the parent view so that
     * {@link #safelyTerminatePipeline()} is called automatically if the parent is
     * detached from the window (e.g. when navigating away or destroying an Activity).
     */
    private void initLifecycleGuard() {
        View parent = parentViewRef.get();
        if (parent == null) return;

        stateChangeListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                safelyTerminatePipeline();
            }
        };
        parent.addOnAttachStateChangeListener(stateChangeListener);
    }

    /** Sets the animation duration in milliseconds. Default is 400 ms. */
    public LinearSlideAnimator setDuration(long duration) {
        this.duration = duration;
        return this;
    }

    /** Sets the delay before the first view begins animating, in milliseconds. */
    public LinearSlideAnimator setStartDelay(long startDelay) {
        this.startDelay = startDelay;
        return this;
    }

    /**
     * Overrides the default interpolator. By default, *_IN directions use
     * {@link DecelerateInterpolator} and *_OUT directions use {@link AccelerateInterpolator}.
     */
    public LinearSlideAnimator setInterpolator(TimeInterpolator interpolator) {
        this.customInterpolator = interpolator;
        return this;
    }

    /**
     * Changes the slide direction on an existing instance, allowing a single
     * {@code LinearSlideAnimator} to be reused for both in and out passes without
     * constructing a new object.
     *
     * <p>Any in-progress animation is cancelled before the direction is updated.
     * Call {@link #resetTranslations()} afterward if you need to clear the cached
     * translation baselines before animating in the new direction.
     *
     * @param direction The new slide direction.
     */
    public LinearSlideAnimator setDirection(Direction direction) {
        cancel();
        this.direction = direction;
        return this;
    }

    /**
     * Controls the stagger between consecutive animated views.
     * Each view n starts at: {@code startDelay + duration * n * reactionFactor}.
     * Default is 0.2, giving a subtle cascading feel.
     *
     * @param reactionFactor 0.0 = simultaneous, 1.0 = each view waits one full duration.
     */
    public LinearSlideAnimator setReactionFactor(float reactionFactor) {
        this.reactionFactor = reactionFactor;
        return this;
    }

    /** Enables alpha cross-fading in sync with the translation animation. */
    public LinearSlideAnimator withAlpha() {
        this.withAlpha = true;
        return this;
    }

    /**
     * Enables automatic visibility management using {@link View#GONE}.
     * *_IN targets will be GONE before animating and become VISIBLE when the animation starts.
     * *_OUT targets become GONE when the animation ends.
     */
    public LinearSlideAnimator autoVisibility() {
        return autoVisibility(true);
    }

    /**
     * Internal implementation of autoVisibility that accepts a flag controlling whether
     * GONE (true) or INVISIBLE (false) is used for pre-animation hiding.
     */
    public LinearSlideAnimator autoVisibility(boolean gone) {
        this.autoVisibility = true;
        boolean isInAnimation =
                direction == Direction.LEFT_IN
                        || direction == Direction.UP_IN
                        || direction == Direction.RIGHT_IN
                        || direction == Direction.DOWN_IN;

        if (isInAnimation) {
            for (WeakReference<View> ref : targetViewsRefs) {
                View v = ref.get();
                if (v != null) {
                    v.setVisibility(gone ? View.GONE : View.INVISIBLE);
                }
            }
        }
        return this;
    }

    /** Adds a lifecycle listener. Duplicate or null listeners are ignored. */
    public LinearSlideAnimator addListener(AnimatorListener listener) {
        if (listener != null) this.listeners.add(listener);
        return this;
    }

    /** Removes a previously registered listener. */
    public LinearSlideAnimator removeListener(AnimatorListener listener) {
        this.listeners.remove(listener);
        return this;
    }

    /**
     * Marks the next {@link #animate()} call to run in reverse — meaning the iteration
     * order of views is reversed and the from/to translation values are swapped.
     * The flag is automatically cleared after one use.
     */
    public LinearSlideAnimator reverse() {
        this.withReverse = true;
        return this;
    }

    /** Returns true if any ValueAnimator is currently running. */
    public boolean isRunning() {
        return !runningAnimators.isEmpty();
    }

    /**
     * Clears the cached translationX/Y baselines on all target views, forcing
     * {@link #cacheOriginalTranslation} to re-read the current translation values
     * on the next {@link #animate()} call.
     *
     * <p>Use this when an external caller (e.g. a MotionLayout transition or a
     * shared-element transition) has repositioned target views between animation runs
     * and the stored baseline is no longer valid.
     */
    public LinearSlideAnimator resetTranslations() {
        for (WeakReference<View> ref : targetViewsRefs) {
            View v = ref.get();
            if (v != null) {
                v.setTag(TAG_TRANSLATION_KEY, null);
            }
        }
        return this;
    }

    /**
     * Cancels all running animations immediately without completing them.
     * Hardware layers are reverted and all {@link AnimatorListener#onAnimationCancel} callbacks
     * are fired. Safe to call when no animation is in progress.
     */
    public void cancel() {
        if (!runningAnimators.isEmpty()) {
            List<ValueAnimator> animatorsToCancel = new ArrayList<>(runningAnimators);
            runningAnimators.clear();
            for (ValueAnimator a : animatorsToCancel) {
                a.cancel();
            }
            for (AnimatorListener listener : listeners) {
                listener.onAnimationCancel(this);
            }
            revertHardwareLayers();
        }
    }

    /**
     * Cancels running animations and removes all observer registrations, preventing
     * callbacks from firing after the parent view has been detached from the window.
     * Clears the target view list and listener list to release references.
     */
    private void safelyTerminatePipeline() {
        cancel();
        View parent = parentViewRef.get();
        if (parent != null && stateChangeListener != null) {
            parent.removeOnAttachStateChangeListener(stateChangeListener);
        }
        if (parent != null && dynamicLayoutObserver != null) {
            parent.getViewTreeObserver().removeOnGlobalLayoutListener(dynamicLayoutObserver);
        }
        targetViewsRefs.clear();
        listeners.clear();
    }

    /**
     * Starts the animation pipeline.
     *
     * <p>Execution order:
     * <ol>
     *   <li>Any in-progress animation is cancelled.</li>
     *   <li>If the parent already has valid dimensions, target views are pre-positioned
     *       off-screen immediately. Otherwise a {@link ViewTreeObserver.OnGlobalLayoutListener}
     *       defers pre-positioning until the layout pass completes.</li>
     *   <li>A {@link View#post} call schedules the actual ValueAnimator launch on the next
     *       frame, ensuring that the pre-positioning has been rendered before motion begins.
     *       This eliminates the visual glitch that would occur if cancel() and the first
     *       animation frame happened within the same frame.</li>
     * </ol>
     */
    public void animate() {
        View parent = parentViewRef.get();
        if (parent == null) return;
       
        cancel();

        Rect earlyParentRect = new Rect(getPossibleRect(parent));
        if (earlyParentRect.width() > 0 && earlyParentRect.height() > 0) {
            prePositionViews(earlyParentRect);
        } else {
            setupDynamicLayoutObserver(parent);
        }

        parent.post(
                () -> {
                    View currentParent = parentViewRef.get();
                    if (currentParent == null) return;

                    parentRect = new Rect(getPossibleRect(currentParent));
                    final boolean isReverseExecution = withReverse;
                    withReverse = false;

                    boolean isInAnim =
                            direction == Direction.LEFT_IN
                                    || direction == Direction.UP_IN
                                    || direction == Direction.RIGHT_IN
                                    || direction == Direction.DOWN_IN;

                    if (isInAnim && autoVisibility && !withReverse) autoVisibility(false);

                    // Choose interpolator: decelerate for _IN (ease into position),
                    // accelerate for _OUT (build speed as leaving). Reversed animations
                    // use the opposite curve so the motion still feels natural.
                    TimeInterpolator targetInterpolator;
                    if (customInterpolator != null) {
                        targetInterpolator = customInterpolator;
                    } else if (isInAnim) {
                        targetInterpolator =
                                isReverseExecution
                                        ? new AccelerateInterpolator(2.5f)
                                        : new DecelerateInterpolator(2.5f);
                    } else {
                        targetInterpolator =
                                isReverseExecution
                                        ? new DecelerateInterpolator(2.5f)
                                        : new AccelerateInterpolator(2.5f);
                    }

                    for (AnimatorListener listener : listeners) {
                        listener.onAnimationStart(this);
                    }

                    applyHardwareLayers();

                    // UP_IN and DOWN_OUT animate from the last child to the first so that
                    // lower views appear to fall into place after upper ones — matching
                    // natural reading order. All other directions iterate forward.
                    if (direction == Direction.UP_IN && !isReverseExecution) {
                        int step = 0;
                        for (int i = targetViewsRefs.size() - 1; i >= 0; i--) {
                            processViewItem(
                                    targetViewsRefs.get(i).get(),
                                    step++,
                                    isReverseExecution,
                                    isInAnim,
                                    targetInterpolator);
                        }
                        return;
                    } else if (direction == Direction.DOWN_OUT && !isReverseExecution) {
                        int step = 0;
                        for (int i = targetViewsRefs.size() - 1; i >= 0; i--) {
                            processViewItem(
                                    targetViewsRefs.get(i).get(),
                                    step++,
                                    isReverseExecution,
                                    isInAnim,
                                    targetInterpolator);
                        }
                        return;
                    }

                    // For reverse executions (other than UP_IN / DOWN_OUT), iterate backward
                    // so the last view that appeared is the first to leave — preserving symmetry.
                    if (isReverseExecution) {
                        if (direction != Direction.UP_IN && direction != Direction.DOWN_OUT) {
                            int step = 0;
                            for (int i = targetViewsRefs.size() - 1; i >= 0; i--) {
                                processViewItem(
                                        targetViewsRefs.get(i).get(),
                                        step++,
                                        isReverseExecution,
                                        isInAnim,
                                        targetInterpolator);
                            }
                        } else {
                            for (int i = 0; i < targetViewsRefs.size(); i++) {
                                processViewItem(
                                        targetViewsRefs.get(i).get(),
                                        i,
                                        isReverseExecution,
                                        isInAnim,
                                        targetInterpolator);
                            }
                        }
                    } else {
                        for (int i = 0; i < targetViewsRefs.size(); i++) {
                            processViewItem(
                                    targetViewsRefs.get(i).get(),
                                    i,
                                    isReverseExecution,
                                    isInAnim,
                                    targetInterpolator);
                        }
                    }
                });
    }

    /**
     * Registers a one-shot {@link ViewTreeObserver.OnGlobalLayoutListener} that waits for
     * the parent to have non-zero dimensions, then calls {@link #prePositionViews(Rect)}
     * and removes itself.
     */
    private void setupDynamicLayoutObserver(final View parent) {
        dynamicLayoutObserver = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                Rect freshRect = new Rect(getPossibleRect(parent));
                if (freshRect.width() > 0 && freshRect.height() > 0) {
                    prePositionViews(freshRect);
                    parent.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            }
        };
        parent.getViewTreeObserver().addOnGlobalLayoutListener(dynamicLayoutObserver);
    }

    /**
     * Computes and starts a single view's ValueAnimator.
     *
     * <p>The method restores any previously cached translation first, then computes the
     * off-screen start/end translation based on the view's position relative to the
     * parent bounds. Reverse execution swaps {@code from} and {@code to}.
     *
     * @param v                The view to animate.
     * @param step             Zero-based index used to calculate the stagger delay.
     * @param isReverseExecution True if this run is reversed.
     * @param isInAnimation    True for *_IN directions.
     * @param interpolator     The time interpolator to apply.
     */
    private void processViewItem(
            View v,
            int step,
            boolean isReverseExecution,
            boolean isInAnimation,
            TimeInterpolator interpolator) {
        if (v == null) return;

        boolean useX = direction == Direction.LEFT_OUT
                || direction == Direction.LEFT_IN
                || direction == Direction.RIGHT_IN
                || direction == Direction.RIGHT_OUT;

        // Restore any cached original translation so that offset calculations are
        // always relative to the view's natural on-screen position.
        float cachedT = v.getTag(TAG_TRANSLATION_KEY) != null
                ? (float) v.getTag(TAG_TRANSLATION_KEY) : 0f;
        if (useX) v.setTranslationX(cachedT);
        else v.setTranslationY(cachedT);

        Rect vRect = new Rect(getPossibleRect(v));
        long finalDelay = computeDelay(step);

        float from = 0f, to = 0f;
        android.util.Property<View, Float> targetProp = useX
                ? View.TRANSLATION_X : View.TRANSLATION_Y;
        boolean outAnimation = false;

        // For each direction, compute the pixel distance required to move the view
        // fully off-screen on the appropriate edge, then assign from/to accordingly.
        if (direction == Direction.LEFT_OUT) {
            float x = computeXOffset(vRect, parentRect, false);
            float offScreen = isNegative(x) ? x : -x;
            from = isReverseExecution ? offScreen : cachedT;
            to = isReverseExecution ? cachedT : offScreen;
            outAnimation = true;
        } else if (direction == Direction.LEFT_IN) {
            float x = computeXOffset(vRect, parentRect, false);
            float offScreen = isNegative(x) ? x : -x;
            from = isReverseExecution ? cachedT : offScreen;
            to = isReverseExecution ? offScreen : cachedT;
        } else if (direction == Direction.UP_OUT) {
            float y = computeYOffset(vRect, parentRect, false);
            float offScreen = isNegative(y) ? y : -y;
            from = isReverseExecution ? offScreen : cachedT;
            to = isReverseExecution ? cachedT : offScreen;
            outAnimation = true;
        } else if (direction == Direction.UP_IN) {
            float y = computeYOffset(vRect, parentRect, false);
            float offScreen = isNegative(y) ? y : -y;
            from = isReverseExecution ? cachedT : offScreen;
            to = isReverseExecution ? offScreen : cachedT;
        } else if (direction == Direction.RIGHT_IN) {
            float x = computeXOffset(vRect, parentRect, true);
            float offScreen = isNegative(x) ? -x : x;
            from = isReverseExecution ? cachedT : offScreen;
            to = isReverseExecution ? offScreen : cachedT;
        } else if (direction == Direction.RIGHT_OUT) {
            float x = computeXOffset(vRect, parentRect, true);
            float offScreen = isNegative(x) ? -x : x;
            from = isReverseExecution ? offScreen : cachedT;
            to = isReverseExecution ? cachedT : offScreen;
            outAnimation = true;
        } else if (direction == Direction.DOWN_IN) {
            float y = computeYOffset(vRect, parentRect, true);
            float offScreen = isNegative(y) ? -y : y;
            from = isReverseExecution ? cachedT : offScreen;
            to = isReverseExecution ? offScreen : cachedT;
        } else if (direction == Direction.DOWN_OUT) {
            float y = computeYOffset(vRect, parentRect, true);
            float offScreen = isNegative(y) ? -y : y;
            from = isReverseExecution ? offScreen : cachedT;
            to = isReverseExecution ? cachedT : offScreen;
            outAnimation = true;
        }

        startValueAnimator(v, targetProp, from, to, finalDelay,
                interpolator, isInAnimation, isReverseExecution, outAnimation);
    }

    /**
     * Returns all direct children of the given {@link ViewGroup} as a flat list.
     * Used by the single-argument constructor to auto-collect targets.
     */
    private List<View> extractViews(ViewGroup parent) {
        List<View> vs = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            vs.add(parent.getChildAt(i));
        }
        return vs;
    }

    /**
     * Moves *_IN target views to their off-screen starting positions before the animation
     * begins. Called as early as possible so the views are never seen in their default
     * on-screen position. For *_OUT directions this is a no-op because the views should
     * be visible at their natural position before the out-animation starts.
     *
     * @param pRect The bounding rectangle of the parent in screen coordinates.
     */
    private void prePositionViews(Rect pRect) {
        boolean isInAnimation =
                direction == Direction.LEFT_IN
                        || direction == Direction.UP_IN
                        || direction == Direction.RIGHT_IN
                        || direction == Direction.DOWN_IN;

        for (WeakReference<View> ref : targetViewsRefs) {
            View v = ref.get();
            if (v == null) continue;
            Rect vRect = new Rect(getPossibleRect(v));

            if (direction == Direction.LEFT_IN || direction == Direction.LEFT_OUT) {
                float x = computeXOffset(vRect, pRect, false);
                float offScreen = isNegative(x) ? x : -x;
                cacheOriginalTranslation(v, true);
                if (isInAnimation) v.setTranslationX(withReverse ? v.getTranslationX() : offScreen);
            } else if (direction == Direction.RIGHT_IN || direction == Direction.RIGHT_OUT) {
                float x = computeXOffset(vRect, pRect, true);
                float offScreen = isNegative(x) ? -x : x;
                cacheOriginalTranslation(v, true);
                if (isInAnimation) v.setTranslationX(withReverse ? v.getTranslationX() : offScreen);
            } else if (direction == Direction.UP_IN || direction == Direction.UP_OUT) {
                float y = computeYOffset(vRect, pRect, false);
                float offScreen = isNegative(y) ? y : -y;
                cacheOriginalTranslation(v, false);
                if (isInAnimation) v.setTranslationY(withReverse ? v.getTranslationY() : offScreen);
            } else if (direction == Direction.DOWN_IN || direction == Direction.DOWN_OUT) {
                float y = computeYOffset(vRect, pRect, true);
                float offScreen = isNegative(y) ? -y : y;
                cacheOriginalTranslation(v, false);
                if (isInAnimation) v.setTranslationY(withReverse ? v.getTranslationY() : offScreen);
            }
        }
    }

    /**
     * Returns the stagger-adjusted start delay for the view at position {@code step}.
     * Step 0 receives exactly {@code startDelay}; subsequent steps add a fraction of
     * {@code duration} proportional to {@link #reactionFactor}.
     */
    private long computeDelay(int step) {
        return (step == 0) ? startDelay : startDelay + (long) ((duration * step) * reactionFactor);
    }

    /**
     * Calculates the horizontal pixel offset needed to move a view fully off-screen.
     *
     * @param vRect     The view's bounding rectangle in screen coordinates.
     * @param pRect     The parent's bounding rectangle in screen coordinates.
     * @param rightward True to compute the right-edge offset; false for the left edge.
     * @return A positive pixel distance; callers negate it as needed for direction.
     */
    private float computeXOffset(Rect vRect, Rect pRect, boolean rightward) {
        if (rightward) {
            return vRect.right < pRect.right
                    ? pRect.right - vRect.left
                    : vRect.right == pRect.right ? vRect.width() : 0;
        } else {
            return vRect.left > pRect.left
                    ? vRect.left + vRect.width()
                    : vRect.left == pRect.left ? vRect.width() : 0;
        }
    }

    /**
     * Calculates the vertical pixel offset needed to move a view fully off-screen.
     *
     * @param vRect    The view's bounding rectangle in screen coordinates.
     * @param pRect    The parent's bounding rectangle in screen coordinates.
     * @param downward True to compute the bottom-edge offset; false for the top edge.
     * @return A positive pixel distance; callers negate it as needed for direction.
     */
    private float computeYOffset(Rect vRect, Rect pRect, boolean downward) {
        if (downward) {
            return vRect.bottom < pRect.bottom
                    ? pRect.bottom - vRect.top
                    : vRect.bottom == pRect.bottom ? vRect.height() : 0;
        } else {
            return vRect.top > pRect.top
                    ? vRect.top + vRect.height()
                    : vRect.top == pRect.top ? vRect.height() : 0;
        }
    }

    /**
     * Stores the view's current translationX or translationY in a tag so it can be
     * restored after an *_OUT animation or a cancelled run. Only written once; subsequent
     * calls are no-ops so that re-triggering the animation does not overwrite the baseline.
     *
     * @param v    The target view.
     * @param useX True to cache translationX; false to cache translationY.
     */
    private void cacheOriginalTranslation(View v, boolean useX) {
        if (v.getTag(TAG_TRANSLATION_KEY) == null) {
            v.setTag(TAG_TRANSLATION_KEY, useX ? v.getTranslationX() : v.getTranslationY());
        }
    }

    /**
     * Switches all target views to {@link View#LAYER_TYPE_HARDWARE} for the duration
     * of the animation. The original layer type is cached so it can be restored exactly.
     * Hardware layers offload compositing to the GPU, significantly reducing CPU usage
     * during property animations.
     */
    private void applyHardwareLayers() {
        for (WeakReference<View> ref : targetViewsRefs) {
            View v = ref.get();
            if (v != null) {
                if (v.getTag(TAG_LAYER_TYPE_KEY) == null) {
                    v.setTag(TAG_LAYER_TYPE_KEY, v.getLayerType());
                }
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }
    }

    /**
     * Restores each view's layer type to the value captured in {@link #applyHardwareLayers()}.
     * Falls back to {@link View#LAYER_TYPE_NONE} if no cached value is found.
     * Should be called whenever animation ends or is cancelled.
     */
    private void revertHardwareLayers() {
        for (WeakReference<View> ref : targetViewsRefs) {
            View v = ref.get();
            if (v != null) {
                Object layerTag = v.getTag(TAG_LAYER_TYPE_KEY);
                int defaultLayerType = (layerTag instanceof Integer)
                        ? (Integer) layerTag : View.LAYER_TYPE_NONE;
                v.setLayerType(defaultLayerType, null);
            }
        }
    }

    /**
     * Attempts to determine the view's bounding rectangle in screen coordinates using
     * a cascade of three strategies, falling back to screen dimensions as a last resort.
     *
     * <ol>
     *   <li>{@link View#getLocationOnScreen} — accurate after the first layout pass.</li>
     *   <li>Layout bounds ({@link View#getLeft}, {@link View#getTop}, etc.) — available
     *       when the view is laid out but not yet drawn.</li>
     *   <li>{@link View#getGlobalVisibleRect} — handles partially clipped views.</li>
     *   <li>Full screen dimensions — used only when all other methods return zero sizes,
     *       ensuring the animation always has some valid reference bounds.</li>
     * </ol>
     */
    private Rect getPossibleRect(View view) {
        Rect rect = new Rect();
        if (view == null) return rect;

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int screenWidth = view.getWidth();
        int screenHeight = view.getHeight();

        if ((location[0] != 0 || location[1] != 0) && screenWidth > 0 && screenHeight > 0) {
            rect.left = location[0];
            rect.top = location[1];
            rect.right = rect.left + screenWidth;
            rect.bottom = rect.top + screenHeight;
            return rect;
        }

        if (screenWidth > 0 && screenHeight > 0) {
            rect.left = view.getLeft();
            rect.top = view.getTop();
            rect.right = view.getRight();
            rect.bottom = view.getBottom();
            return rect;
        }

        if (view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
            return rect;
        }

        rect.right = view.getContext().getResources().getDisplayMetrics().widthPixels;
        rect.bottom = view.getContext().getResources().getDisplayMetrics().heightPixels;
        return rect;
    }

    /**
     * Creates, configures, and starts a {@link ValueAnimator} for a single view.
     *
     * <p>The animator drives the view's {@code TRANSLATION_X} or {@code TRANSLATION_Y}
     * property from {@code from} to {@code to}. Alpha is updated in the same update
     * listener when {@link #withAlpha} is enabled.
     *
     * <p>When the last running animator ends, hardware layers are reverted and
     * {@link AnimatorListener#onAnimationEnd} callbacks are fired.
     *
     * @param v            The view to animate.
     * @param property     Either {@link View#TRANSLATION_X} or {@link View#TRANSLATION_Y}.
     * @param from         Starting translation in pixels.
     * @param to           Ending translation in pixels.
     * @param delay        Start delay in milliseconds.
     * @param interpolator The time interpolator.
     * @param isInAnimation True for *_IN directions (alpha goes 0→1).
     * @param reverse      True if this is a reversed run (alpha direction inverts).
     * @param outAnimation True if the animation slides the view off-screen.
     */
    private void startValueAnimator(
            final View v,
            final android.util.Property<View, Float> property,
            float from,
            float to,
            long delay,
            TimeInterpolator interpolator,
            final boolean isInAnimation,
            final boolean reverse,
            final boolean outAnimation) {

        final ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(duration);
        a.setStartDelay(delay);
        a.setInterpolator(interpolator);

        if (withAlpha) {
            v.setAlpha(isInAnimation ? (reverse ? 1f : 0f) : (reverse ? 0f : 1f));
        }

        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                // Make view visible at the frame it actually starts moving so there
                // is no gap between the visibility change and the first translated frame.
                if (autoVisibility && ((!isInAnimation && reverse) || (isInAnimation && !reverse))) {
                    v.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (outAnimation && !reverse) v.setVisibility(View.GONE);
                runningAnimators.remove(a);
                // Only fire the global end callback once all staggered views have finished.
                if (runningAnimators.isEmpty()) {
                    revertHardwareLayers();
                    for (AnimatorListener listener : listeners) {
                        listener.onAnimationEnd(LinearSlideAnimator.this);
                    }
                }
            }
        });

        a.addUpdateListener(animation -> {
            property.set(v, (Float) animation.getAnimatedValue());
            if (withAlpha) {
                float fraction = animation.getAnimatedFraction();
                if (isInAnimation) {
                    v.setAlpha(reverse ? (1f - fraction) : fraction);
                } else {
                    v.setAlpha(reverse ? fraction : (1f - fraction));
                }
            }
        });

        runningAnimators.add(a);
        a.start();
    }

    /** Returns true if {@code n} is strictly less than zero. */
    private boolean isNegative(float n) {
        return n < 0;
    }
}
