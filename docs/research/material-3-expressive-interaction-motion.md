# Material 3 Expressive interaction and motion

Researched 2026-08-22 from the Material 3 specification, Android developer
documentation, and AndroidX source. AndroidX source links below are pinned to
commit `ff9a7111302243197384c499d5e3461c1804cd6e` so the findings remain
reproducible.

## Conclusions

### Use motion by property, not one animation for everything

Material 3 divides motion into three speeds and two kinds:

- **Spatial** motion is for position, rotation, size, bounds, and shape. It may
  overshoot in the expressive scheme.
- **Effects** motion is for color and opacity. It is critically damped so those
  values do not overshoot.

The expressive spring tokens currently shipped by AndroidX are:

| Token | Damping | Stiffness | Appropriate KGS use |
| --- | ---: | ---: | --- |
| fast spatial | 0.6 | 800 | press response, a chevron/icon state change, menu geometry |
| default spatial | 0.8 | 380 | ordinary layout and placement changes |
| slow spatial | 0.8 | 200 | prominent transitions with room to settle |
| fast effects | 1.0 | 3800 | quick color/alpha response |
| default effects | 1.0 | 1600 | ordinary color/alpha response and non-bouncy button shape morphs |
| slow effects | 1.0 | 800 | slower color/alpha transitions |

These are not approximate values: they are the generated
[ExpressiveMotionTokens](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ExpressiveMotionTokens.kt).
The public [`MotionScheme`](https://developer.android.com/reference/kotlin/androidx/compose/material3/MotionScheme)
API makes the same six categories available through `MaterialTheme.motionScheme`.
Android describes the expressive scheme as the recommended scheme for prominent
elements and hero interactions; applying it to every KGS control is therefore a
deliberate product choice, not a universal Material requirement.

Springs should own the whole state change. Compose springs preserve velocity when
interrupted, which is why they respond naturally to rapid input. Chaining a manual
pulse, rebound, delay, or tween after the spring defeats that property and commonly
produces the staged or double-moving feel reported in the app. See Android's
[spring guidance](https://developer.android.com/develop/ui/compose/animation/customize#create-physics-based-animation-with-spring).

### Phone buttons morph shape; a global press-scale is not the M3 component behavior

The expressive overloads of phone `Button` and `IconButton` observe a shared
`MutableInteractionSource` and morph between normal and pressed container shapes.
For a small round button/icon button, the generated tokens change from a fully
round container to an 8 dp `CornerSmall` container while pressed:

- [`ButtonSmallTokens`](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ButtonSmallTokens.kt)
- [`SmallIconButtonTokens`](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/SmallIconButtonTokens.kt)
- [`Button` implementation](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Button.kt)
- [`IconButton` implementation](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/IconButton.kt)

Those implementations intentionally use the default **effects** spec for the
pressed shape, with a source comment that this prevents bounce in the component.
By contrast, toggle buttons use fast spatial motion to move among unchecked,
pressed, and checked shapes, and checked state also has its own container/content
color: [`ToggleButton`](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ToggleButton.kt).

Material buttons still produce `PressInteraction.Press`, `Release`, and `Cancel`
and retain a ripple/state-layer indication. Android explicitly describes Material
buttons as both producing interactions and consuming them to show ripple and
elevation feedback. A persistent selected container is not a replacement for
transient press feedback. See
[Handling user interactions](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/handling-interactions).

The scale-down example in that same Android document is explicitly a **custom
indication** example, not the phone Material 3 button implementation. Therefore a
single `scale = 0.94` modifier on every clickable element should not be treated as
the route to M3 Expressive fidelity.

The conspicuous grow-and-compress response seen in Material's adjacent-button
examples belongs to **ButtonGroup**, not to every standalone button. The pressed
child expands by 15% of its width by default while its neighbor or neighbors
compress, all with fast spatial motion. AndroidX also waits until the press
animation is more than 75% complete before animating back, ensuring that even a
quick tap remains perceptible rather than flickering. See the pinned
[`ButtonGroup` implementation](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ButtonGroup.kt)
and public [`ButtonGroup` API](https://developer.android.com/reference/kotlin/androidx/compose/material3/ButtonGroup.composable).
That behavior is a strong match for KGS's filter cluster and connected sort pair;
it should not be copied onto isolated toolbar actions.

Actionable KGS contract:

1. Use action buttons for one-shot actions and toggle/icon-toggle buttons for
   filters and persistent modes.
2. Give a toggle separate idle, pressed, and selected visuals. Pressed temporarily
   wins; release settles into the selected or idle state.
3. Drive shape, container, clipping, semantics, and the bounded ripple from one
   interaction source and one shape. Stacking a second press overlay on a selected
   container is a likely source of flicker.
4. Keep bounded ripple feedback on undo, redo, and other actions. Do not set the
   indication to `null` merely because another button elsewhere flickered.
5. Replace the shared all-control scale animation with the component-appropriate
   shape morph. For small round KGS icon controls, round-to-8 dp is the official
   reference; KGS may tune the idle shape while keeping the state model intact.
6. For the filter cluster and the connected sort-direction/sort-selector pair,
   use `ButtonGroup`/`animateWidth` when available. If the experimental API cannot
   be adopted, reproduce its exact interaction model: 15% pressed-child growth,
   neighbor compression, fast spatial spring, and a return only after press
   progress exceeds 0.75.

### Let `DropdownMenu` perform its own placement and motion

Compose `DropdownMenu` is a popup window. It tries below its anchor first, then
above it, and finally against the window edge so that it remains visible. An
offset is intended only for the difference between an anchor's layout and visual
bounds. This means a toolbar menu should be anchored to its toolbar button and be
allowed to select the space above the IME; a fixed negative offset based on an
assumed menu height is brittle and can overlap or clip the toolbar. See the
[`DropdownMenu` API placement rules](https://developer.android.com/reference/kotlin/androidx/compose/material3/DropdownMenu.composable).

`DropdownMenu` already implements expressive open/close motion internally:

- scale from `0.8` to `1.0` with **fast spatial** motion;
- alpha from `0` to `1` with **fast effects** motion;
- a transform origin calculated from the real anchor/menu intersection.

The implementation is in AndroidX
[`Menu.kt`](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Menu.kt).
Adding another scale/alpha modifier around `DropdownMenu` doubles the motion and
loses its calculated transform origin. Remove the extra animation instead of
tuning two animations against each other.

### Menus should retain elevation; softness is a separate tuning dimension

The Material menu token uses shadow elevation Level 2, which is 3 dp:

- [`MenuTokens`](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/MenuTokens.kt)
- [`ElevationTokens`](https://android.googlesource.com/platform/frameworks/support/+/ff9a7111302243197384c499d5e3461c1804cd6e/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ElevationTokens.kt)

Material 3 uses tonal elevation in addition to shadows, and `Surface` supports
the two separately. See [Material 3 elevation in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3#elevation).
Removing menu shadow elevation altogether is not faithful to the component.

For the requested larger, washed-out KGS appearance, first restore the semantic
Level 2 shadow. If that is still too compact, add one low-alpha diffuse outer
shadow rather than raising platform elevation until it becomes dark. Compose's
[`dropShadow`](https://developer.android.com/develop/ui/compose/graphics/draw/shadows)
API directly exposes blur radius, spread, offset, and alpha. A reasonable visual
starting point—not a Material token—is a 14–18 dp radius, 1–2 dp spread, 0–4 dp
vertical offset, and black at roughly 8–12% opacity. Apply it to the same rounded
shape as the popup and verify that the popup window does not clip the outset.

### Icon transitions should be one coherent state animation

Rotation is spatial motion. A sort-direction chevron should animate one centered
rotation value directly to its new state with fast spatial motion. The spring's
0.6 damping supplies the expressive overshoot and preserves velocity if the user
reverses direction during the animation. Do not add a separate squash, vertical
jump, or delayed rebound; those animations have no documented Material basis and
make the chevron appear to spin in stages.

For icons whose visual state genuinely changes rather than rotates, Material
Symbols recommends animating the `FILL` axis to communicate a state transition.
See the official [Material Symbols guide](https://developers.google.com/fonts/docs/material_symbols#fill_axis).
An animated vector is the appropriate alternative when two static vector paths
cannot interpolate cleanly. Alpha/color changes use an effects spec, while path,
fill, size, and rotation use the appropriate spatial spec.

## Immediate implementation checklist

- Keep the existing numeric KGS motion tokens—they already match the official
  expressive tokens—but route each property to the right token category.
- Replace the shared press-scale behavior with shared interaction handling plus
  component-specific pressed/selected shape morphs and bounded ripple.
- Give connected filter/sort controls ButtonGroup's fast-spatial 15% child growth
  and neighbor compression instead of scaling the entire cluster.
- Ensure undo and redo use that same press indication contract.
- Use toggle semantics and a durable selected container/shape for library filters
  and editor modes.
- Remove custom menu scale/alpha animation; anchor each menu to its trigger and
  rely on `DropdownMenu` to place toolbar menus above the IME.
- Restore the menu shadow and optionally add a single diffuse low-alpha outer
  `dropShadow` after visual comparison on the emulator.
- Replace the sort chevron pulse/squash sequence with one center-origin fast
  spatial rotation spring.

## Scope caveat

The official sources define component behavior and motion categories, but they do
not prescribe a universal “bounce every button” modifier or the custom washed-out
shadow requested for KGS Notes. The shape-morph/ripple behavior above is specified;
the suggested outer-shadow values are explicitly a KGS visual tuning starting
point.
