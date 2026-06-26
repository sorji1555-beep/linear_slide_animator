# LinearSlideAnimator

A lightweight Android animation library that slides one or more Views in or out of their parent's visible bounds — with stagger, alpha cross-fade, automatic visibility management, and GPU-accelerated rendering built in. No dependencies. Drop in one file, or pull from JitPack.

---

## Installation

Add JitPack to your root `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Then add the dependency in your module `build.gradle`:

```groovy
dependencies {
    implementation 'com.github.YOUR_GITHUB_USERNAME:LinearSlideAnimator:1.0.0'
}
```

Replace `YOUR_GITHUB_USERNAME` with the GitHub account this repo is published under.

> **Minimum API:** 21 &nbsp;|&nbsp; **Java source:** 11

---

## Quick Start

```java
// Slide all children of a ViewGroup in from the bottom with stagger
new LinearSlideAnimator(myLinearLayout, Direction.DOWN_IN)
    .setDuration(350)
    .setReactionFactor(0.15f)
    .withAlpha()
    .autoVisibility()
    .animate();
```

```java
// Slide specific views out to the right
new LinearSlideAnimator(rootView, Direction.RIGHT_OUT, titleView, subtitleView, buttonView)
    .setDuration(300)
    .autoVisibility()
    .animate();
```

```java
// Reuse one instance for both in and out passes
LinearSlideAnimator anim = new LinearSlideAnimator(rootView, Direction.LEFT_IN)
    .setDuration(400)
    .withAlpha()
    .autoVisibility();

anim.animate();                              // slide in
anim.setDirection(Direction.LEFT_OUT).animate(); // slide out later
```

```java
// Listen for lifecycle events
new LinearSlideAnimator(rootView, Direction.UP_IN)
    .addListener(new LinearSlideAnimator.AnimatorListener() {
        @Override public void onAnimationStart(LinearSlideAnimator a) { }
        @Override public void onAnimationEnd(LinearSlideAnimator a) { }
        @Override public void onAnimationCancel(LinearSlideAnimator a) { }
    })
    .animate();
```

---

## API

| Method | Default | Description |
|---|---|---|
| `setDuration(long ms)` | `400` | Duration of each view's animation |
| `setStartDelay(long ms)` | `0` | Delay before the first view starts |
| `setReactionFactor(float)` | `0.2` | Stagger between consecutive views (`0` = simultaneous) |
| `setInterpolator(TimeInterpolator)` | auto | Override the default ease-in/ease-out curve |
| `setDirection(Direction)` | — | Change direction on an existing instance |
| `withAlpha()` | off | Cross-fade alpha in sync with the translation |
| `autoVisibility()` | off | Manage `GONE`/`VISIBLE` automatically around the animation |
| `reverse()` | — | Run the next `animate()` call in reverse (single-use flag) |
| `resetTranslations()` | — | Clear cached translation baselines (use after external repositioning) |
| `addListener(AnimatorListener)` | — | Register a lifecycle callback |
| `removeListener(AnimatorListener)` | — | Remove a lifecycle callback |
| `animate()` | — | Start the animation |
| `cancel()` | — | Cancel immediately |
| `isRunning()` | — | Returns `true` if any animator is active |

### Directions

`LEFT_IN` · `LEFT_OUT` · `RIGHT_IN` · `RIGHT_OUT` · `UP_IN` · `UP_OUT` · `DOWN_IN` · `DOWN_OUT`

---

## How the stagger works

Each view's start delay is:

```
delay(n) = startDelay + (duration × n × reactionFactor)
```

`reactionFactor = 0` starts all views at once. `reactionFactor = 0.2` (default) starts each view 20 % of the duration after the previous one, giving a natural cascade.

---

## Tag key collision

The library stores two values per view using integer tag keys (`0x00FFFF01` and `0x00FFFF02`). These sit outside the range aapt generates for resource IDs. If your app uses integer tag keys in that range, override them once at startup before constructing any instance:

```java
LinearSlideAnimator.setTagKeys(0x00AA0001, 0x00AA0002);
```

---

## Contributing

1. Fork the repo and create a branch.
2. Make your change with tests if applicable.
3. Open a pull request against `main`.

Please open an issue first for significant changes.

---

## License

Distributed under the MIT License — see [LICENSE](LICENSE) for details.
