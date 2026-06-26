# LinearSlideAnimator — Documentation

Full technical reference. For setup and basic usage see [README.md](README.md).

---

## Contents

- [Architecture](#architecture)
- [Constructors](#constructors)
- [Configuration methods](#configuration-methods)
- [Control methods](#control-methods)
- [Direction enum](#direction-enum)
- [AnimatorListener interface](#animatorlistener-interface)
- [Internal pipeline](#internal-pipeline)
- [Offset calculation](#offset-calculation)
- [Stagger model](#stagger-model)
- [Hardware layers](#hardware-layers)
- [Lifecycle safety](#lifecycle-safety)
- [Tag keys](#tag-keys)
- [Edge cases](#edge-cases)

---

## Architecture

```
LinearSlideAnimator
├── WeakReference<View>          parentViewRef
├── List<WeakReference<View>>    targetViewsRefs
├── List<ValueAnimator>          runningAnimators
└── List<AnimatorListener>       listeners
```

Every animation runs in three phases:

1. **Pre-position** — `_IN` targets are translated to their off-screen starting position before the first frame so they are never visible at their natural location.
2. **Post-frame launch** — `View.post()` defers the `ValueAnimator` start to the next frame, ensuring the pre-positioned state has been rendered and any prior `cancel()` has fully settled.
3. **Per-view animation** — each target gets its own `ValueAnimator` with an individual start delay derived from the stagger model.

---

## Constructors

### `LinearSlideAnimator(View parentView, Direction direction)`

Auto-collects all direct children of `parentView` as targets when it is a `ViewGroup`.

| Parameter | Description |
|---|---|
| `parentView` | Container whose screen bounds define the off-screen offset distance. |
| `direction` | Initial slide direction. Can be changed later with `setDirection()`. |

### `LinearSlideAnimator(View parentView, Direction direction, View... views)`

Animates an explicit list of views. Null entries are silently skipped.

| Parameter | Description |
|---|---|
| `parentView` | Container whose screen bounds define the off-screen offset distance. |
| `direction` | Initial slide direction. |
| `views` | One or more target views. May come from anywhere in the hierarchy. |

---

## Configuration methods

All configuration methods return `this` and are safe to chain.

### `setDuration(long ms)`
Duration of a single view's motion in milliseconds. Default `400`.

### `setStartDelay(long ms)`
Delay before the step-0 view begins animating. All subsequent views add their stagger on top of this. Default `0`.

### `setInterpolator(TimeInterpolator)`
Overrides the default curves. When not set:
- `_IN` directions use `DecelerateInterpolator(2.5f)` — eases into position.
- `_OUT` directions use `AccelerateInterpolator(2.5f)` — builds speed on exit.
- Reversed runs swap these so the motion still feels natural.

### `setDirection(Direction)`
Changes the slide direction on an existing instance, allowing a single object to handle both in and out passes. Cancels any running animation before updating the direction. Call `resetTranslations()` afterward if views have been repositioned externally since the last run.

### `setReactionFactor(float)`
Controls the stagger between views. `0.0` = simultaneous, `1.0` = each view waits one full `duration`. Default `0.2`. See [Stagger model](#stagger-model) for the formula.

### `withAlpha()`
Enables alpha cross-fading:
- `_IN`: `0.0 → 1.0`
- `_OUT`: `1.0 → 0.0`
- Reversed runs invert the direction.

### `autoVisibility()`
Manages `View.GONE` / `View.VISIBLE` automatically.

| Phase | Effect |
|---|---|
| Before `_IN` animation | Targets set to `GONE` |
| On `_IN` animation start | Targets set to `VISIBLE` at the exact frame motion begins |
| On `_OUT` animation end | Targets set to `GONE` |

Visibility is applied at `onAnimationStart`, not at `animate()`, to prevent a gap between the visibility change and the first translated frame.

### `addListener(AnimatorListener)` / `removeListener(AnimatorListener)`
Registers or removes a lifecycle callback. Null listeners are ignored. Multiple listeners are supported.

### `reverse()`
Single-use flag. The next `animate()` call will: (1) iterate views in reverse order; (2) swap `from`/`to` translation values. The flag resets automatically after one call to `animate()`.

### `resetTranslations()`
Clears the cached `translationX`/`translationY` baseline from all target views. Call this when an external system (e.g. a `MotionLayout` transition or a shared-element transition) has repositioned views between runs and the stored baseline is stale.

---

## Control methods

### `animate()`
Starts the pipeline. Any in-progress animation is cancelled first. Thread: main only.

### `cancel()`
Cancels all running animators immediately. Hardware layers are reverted. `onAnimationCancel` is fired on all listeners. Safe to call when idle.

### `isRunning()`
Returns `true` if any `ValueAnimator` is currently active.

### `setTagKeys(int translationKey, int layerTypeKey)` *(static)*
Overrides the default View tag keys. Call once before constructing any instance if the defaults (`0x00FFFF01`, `0x00FFFF02`) conflict with tags your app sets directly. The two values must be distinct.

---

## Direction enum

| Value | Axis | Motion |
|---|---|---|
| `LEFT_IN` | X | Enters from the left edge |
| `LEFT_OUT` | X | Exits toward the left edge |
| `RIGHT_IN` | X | Enters from the right edge |
| `RIGHT_OUT` | X | Exits toward the right edge |
| `UP_IN` | Y | Enters from the top edge |
| `UP_OUT` | Y | Exits toward the top edge |
| `DOWN_IN` | Y | Enters from the bottom edge |
| `DOWN_OUT` | Y | Exits toward the bottom edge |

### Special iteration order: `UP_IN` and `DOWN_OUT`

Without reverse, these two directions iterate views **last-to-first**:

- `UP_IN` — the topmost view settles last, landing on top of the already-positioned views beneath it.
- `DOWN_OUT` — the bottommost view exits last.

This produces a top-to-bottom cascading appearance that matches natural reading order.

---

## AnimatorListener interface

```java
public interface AnimatorListener {
    void onAnimationStart(LinearSlideAnimator animator);
    void onAnimationEnd(LinearSlideAnimator animator);
    void onAnimationCancel(LinearSlideAnimator animator);
}
```

| Callback | Fires when |
|---|---|
| `onAnimationStart` | Immediately when `animate()` runs, before any view starts moving |
| `onAnimationEnd` | Once **all** staggered views have finished |
| `onAnimationCancel` | When `cancel()` is called explicitly or via the lifecycle guard |

---

## Internal pipeline

### `prePositionViews(Rect pRect)`
For `_IN` directions, each target view is immediately translated to its off-screen starting coordinate before the first frame is posted. For `_OUT` directions this is a no-op.

### `setupDynamicLayoutObserver(View parent)`
When the parent reports zero dimensions (common during fragment inflation), a `ViewTreeObserver.OnGlobalLayoutListener` is registered. It calls `prePositionViews` once valid dimensions arrive, then removes itself.

### `processViewItem(...)`
Per-view entry point. Restores the cached original translation, reads the view's screen rectangle, computes `from`/`to` pixel values for the correct axis, then calls `startValueAnimator`.

### `startValueAnimator(...)`
Creates `ValueAnimator.ofFloat(from, to)` with the configured duration, delay, and interpolator. `onAnimationStart` handles visibility; `onAnimationEnd` fires the global callback when the last staggered view finishes; `addUpdateListener` drives translation and alpha each frame.

---

## Offset calculation

Off-screen offsets are always computed in screen coordinates relative to the parent rectangle.

### Horizontal (`computeXOffset`)

```
rightward = false  (LEFT edge)
  view.left > parent.left   →  offset = view.left + view.width
  view.left == parent.left  →  offset = view.width
  otherwise                 →  offset = 0

rightward = true  (RIGHT edge)
  view.right < parent.right   →  offset = parent.right - view.left
  view.right == parent.right  →  offset = view.width
  otherwise                   →  offset = 0
```

### Vertical (`computeYOffset`)
Same logic on the Y axis with `top`/`bottom` substituted for `left`/`right`.

### Sign convention
Offsets are returned as positive values. Callers negate them:
- `LEFT_*` → `offScreen = isNegative(x) ? x : -x` (force negative, moves left)
- `RIGHT_*` → `offScreen = isNegative(x) ? -x : x` (force positive, moves right)
- `UP_*` → same as LEFT on Y axis
- `DOWN_*` → same as RIGHT on Y axis

---

## Stagger model

```
delay(n) = startDelay + (duration × n × reactionFactor)
```

Each view animates for exactly `duration` ms regardless of its delay. Total wall-clock time for N views:

```
total = startDelay + (duration × (N - 1) × reactionFactor) + duration
```

---

## Hardware layers

Before animators start, `applyHardwareLayers()` sets every target to `LAYER_TYPE_HARDWARE`. The original type is stored in `TAG_LAYER_TYPE_KEY`. After the animation ends or is cancelled, `revertHardwareLayers()` restores the original type exactly — `LAYER_TYPE_NONE`, `LAYER_TYPE_SOFTWARE`, or `LAYER_TYPE_HARDWARE`.

Leaving a view in `LAYER_TYPE_HARDWARE` after the animation ends wastes GPU memory, which is why the revert step runs on both end and cancel paths.

---

## Lifecycle safety

| Mechanism | Purpose |
|---|---|
| `WeakReference<View>` | Prevents holding destroyed views in memory |
| `View.OnAttachStateChangeListener` | Calls `safelyTerminatePipeline()` when the parent detaches, cancelling all animators and clearing all lists |
| `ViewTreeObserver` cleanup | The layout listener self-removes after first firing; also removed in `safelyTerminatePipeline()` |
| Null checks | Every `WeakReference` dereference is guarded before use |

---

## Tag keys

| Constant | Default value | Stores |
|---|---|---|
| `TAG_TRANSLATION_KEY` | `0x00FFFF01` | Original `translationX` or `translationY` as `Float` |
| `TAG_LAYER_TYPE_KEY` | `0x00FFFF02` | Original layer type as `Integer` |

The defaults sit well above the aapt-generated resource ID ceiling (`0x007FFFFF`) and well below `Integer.MAX_VALUE`, balancing safety on both ends. Override with `setTagKeys(int, int)` if needed.

---

## Edge cases

**Views with pre-existing non-zero translations**
The baseline is cached once and never overwritten during the same session. If an external caller changes `translationX`/`translationY` between runs, call `resetTranslations()` to force a fresh read on the next `animate()`.

**ViewGroups with `clipChildren = false`**
Offset calculations use screen coordinates, not local layout coordinates, so partially off-screen views are handled correctly.

**Fragment views not yet attached**
If `animate()` is called before the fragment's view has a window, `getPossibleRect` falls back to full screen dimensions as the parent rect. The animation still runs correctly; the off-screen offset will equal the full screen dimension rather than the container's.

**Rapid successive `animate()` calls**
Each call cancels the previous run synchronously before starting a new one. The new animation always starts from a clean state.

**`reverse()` is single-use**
The flag is consumed by the next `animate()` call and reset to `false` automatically. Calling `reverse()` multiple times before `animate()` has no additional effect.

**Java 11 compatibility**
The library uses standard `if-else` chains instead of arrow-style switch expressions, making it compatible with `sourceCompatibility JavaVersion.VERSION_11` and above.
