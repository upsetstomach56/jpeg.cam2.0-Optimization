# jpegcam Performance — Handoff Plan

This document is self-contained. A model executing it does not need prior context from chat. Every step has file paths, line ranges, exact patterns to follow, and acceptance criteria.

## Project context (read first)

**Repo:** `/Users/james-macbook/Documents/jpegcam`

**Hard constraints — never violate:**
- Android API 10 (Gingerbread). No Kotlin, no lambdas, no coroutines, no modern Jetpack. AsyncTask only.
- 24MB Dalvik heap (Java side). Native heap is separate and much larger.
- NDK C++11, armeabi + armeabi-v7a, ABI filter set in `app/build.gradle:27`.
- Sony VFAT 8.3 filename constraint for any file the app creates on the SD card. Max 8-char name, max 3-char ext, uppercase.
- Visual output must be bit-identical or imperceptibly different from current. No quality-loss shortcuts.

**Hardware target:** PMCA-compatible Sony cameras. SoC is Sony CXD90014 or CXD90045 (BIONZ X), based on a quad-core ARM Cortex-A5 running Linux. Other ARM cores on the SoC and the SA-DSP image processor are owned by Sony's µITRON-side firmware and are not accessible from the Android sandbox.

**Native pipeline:** `app/src/main/cpp/native-lib.cpp` — JNI entry `processImageNative` at line ~217. libjpeg-turbo decode → row kernel (in `process_kernel.h`) → libjpeg-turbo encode. SIMD is OFF in `app/src/main/cpp/CMakeLists.txt:10` because the assumption is BIONZ X has no NEON silicon.

**Existing worker pool:** lines ~68–119 of `native-lib.cpp`. Four persistent worker threads, condition-variable signalled. **Currently used only on one narrow path** (`use_fast_yuv_texture_chunked`, lines ~343–413): no LUT, texture grain, no chrome/sat/bloom/halation/vignette. Every other path — including every shot with a LUT applied, which is the common case — runs the row kernel single-threaded on one core, leaving three cores idle.

**Current symptom:** ~10–15 seconds to process a half-res shot. User suspects the C++ kernel is the bottleneck but has not measured.

---

## Step 0 — Profile the pipeline (PREREQUISITE, do this first)

**Goal:** Establish whether decode, kernel, or encode dominates the wall-clock time. Without this, every later step is a guess.

**File:** `app/src/main/cpp/native-lib.cpp`, inside `processImageNative` (around lines 225–482).

**Change:** Add four `gettimeofday` checkpoints and write the deltas to logcat AND to the SD-card debug log.

**Pattern to follow:**
- The function already calls `long long st = get_time_ms();` at line ~225. Reuse `get_time_ms()`.
- Add: `long long t_after_decode_init = get_time_ms();` immediately after `jpeg_start_decompress(&cd);` (line ~250).
- Add: `long long t_after_row_loop = get_time_ms();` after the big `if (row_stream_mode) { ... } else { ... }` block ends (line ~478).
- At the end of the function, just before the existing `return JNI_TRUE;` (line ~482), add a single LOGD line with all four deltas: setup, row work, encode finish.
- ALSO write a one-line summary to `JPEGCAM/DEBUG.TXT` via the existing `DebugLog` mechanism. The Java side has `DebugLog.write()`. From C++ we don't have direct access; instead, return the timing as a status string OR add a JNI helper. **Simplest approach:** modify the JNI signature to accept a `jobject` callback, OR (easier) modify `LutEngine.applyLutToJpeg` (Java) to call `System.currentTimeMillis()` before and after the native call and log the wall-clock around it; then have the native side log finer-grained breakdown via LOGD only. The DEBUG.TXT line should look like:
  ```
  PERF: total=14123ms (decode_setup=120ms row_loop=11500ms encode_finish=2500ms) scale=2 W=2900 H=1900
  ```

**Acceptance criteria:**
- Build succeeds.
- After a shot is processed on a real camera (user must do this), `JPEGCAM/DEBUG.TXT` contains a `PERF:` line with all four numbers and they sum approximately to the wall-clock time the user observed.
- The numbers tell us which stage dominates. Report back.

**Risk:** None. Pure instrumentation.

**Estimated effort:** 1–2 hours of code.

---

## Step 1 — Verify or refute the NEON assumption

**Goal:** Confirm whether the Cortex-A5 in PMCA cameras actually lacks NEON. The current `CMakeLists.txt:10` comment says it does, but this is unverified. ARM ships the Cortex-A5 with EITHER a plain VFPv4 FPU OR the NEON Media Processing Engine — Sony picked one. If even one supported camera body has NEON, enabling `WITH_SIMD ON` in libjpeg-turbo gives 2–6× speedup on JPEG decode and encode for that body, free.

**Approach:** Add code to dump `/proc/cpuinfo` to the SD-card debug log on app start.

**File:** Either `MainActivity.java` (preferred — runs once on launch), or a one-shot in `Application.onCreate` if there is one.

**Pattern to follow:**
- Use `BufferedReader` + `FileReader("/proc/cpuinfo")`.
- Read all lines, write each to `DebugLog.write()` with prefix `CPUINFO: `.
- Wrap in try/catch — `/proc/cpuinfo` should always be readable on Linux but be defensive.

**Code sketch:**
```java
try {
    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"));
    String line;
    while ((line = br.readLine()) != null) {
        DebugLog.write("CPUINFO: " + line);
    }
    br.close();
} catch (Exception e) {
    DebugLog.write("CPUINFO: read failed: " + e.getMessage());
}
```

Also add a one-time log of `Runtime.getRuntime().availableProcessors()` to confirm Java sees all 4 cores:
```java
DebugLog.write("CPUINFO: availableProcessors=" + Runtime.getRuntime().availableProcessors());
```

**Acceptance criteria:**
- Build succeeds.
- After app launch on a real camera, `DEBUG.TXT` contains `CPUINFO:` lines, including a `Features:` line. The presence or absence of the literal token `neon` in that line answers the question.
- The `availableProcessors` line shows a number (expected: 4, but verify).
- Report findings back to the user.

**Risk:** None. Read-only.

**Estimated effort:** 30 minutes.

---

## Step 2 — Multi-thread the LUT path (THE BIG ONE)

**Goal:** Use all 4 cores for the row kernel in the common case (LUT applied, with or without effects). Currently only the narrow `use_fast_yuv_texture_chunked` path uses the worker pool. Expected speedup: 2.5–3.5× on the kernel portion of total time.

**File:** `app/src/main/cpp/native-lib.cpp`.

**Existing pattern to copy from:** Lines ~343–413, the `use_fast_yuv_texture_chunked` branch. It already:
1. Reads up to `CHK` rows into the row buffer.
2. Splits the chunk across `active_workers` threads.
3. Dispatches via `g_pool` and waits for completion.
4. Writes the chunk to the encoder.

**What to do:**

### 2a. Generalize the worker task struct.
Currently `YuvTextureFastRowsTask` (lines ~33–46) only carries the parameters `process_row_yuv_texture_fast` needs. Create a more general `RowKernelTask` that can carry the parameters for ANY of the three row kernels:
- `process_row_rgb` (used when `use_rgb == true`, i.e. LUT is applied)
- `process_row_yuv` (used when `use_rgb == false` and not the fast path)
- `process_row_yuv_texture_fast` (existing fast path)

The struct should hold:
- The input/output base pointer, row stride, width
- The starting Y, the row range (rowStart/rowEnd)
- A "kernel kind" enum or function pointer telling the worker which kernel to call
- All the per-kernel parameters (some are common — width, ay, scaleDenom, grain, etc.; some are kernel-specific). A union or just a flat struct with all fields is fine for clarity.

### 2b. Refactor the worker pool dispatch to take a task type.
Replace `process_yuv_texture_fast_rows(YuvTextureFastRowsTask*)` with `dispatch_row_kernel(RowKernelTask*)` that switches on the kernel kind and calls the right `process_row_*` for each row in `[rowStart, rowEnd)`.

### 2c. Convert the single-threaded LUT loop (lines ~414–441) to chunk-and-dispatch.
Today it reads one row at a time and calls `process_row_rgb` or `process_row_yuv` directly. Restructure to:
1. Read up to `CHK` rows into `r[0..CHK-1]` (same as the existing fast path).
2. If `applyCrop`, skip rows outside `[sk, sk+fh)` — current per-row logic must be preserved.
3. Build N tasks splitting the chunk across `active_workers` cores.
4. Dispatch via the pool.
5. Write the chunk out.

### 2d. Bloom/halation path (lines ~442–477) — separate handling.
This path uses a 21-row vertical window, so the bloom pass intrinsically reads rows above and below the current row. **Do NOT parallelize the bloom pass itself** — it has cross-row dependencies that would require careful boundary handling. Instead:
1. Run the bloom pass single-threaded into `orw[]` exactly as it does today.
2. The downstream per-row kernel (`process_row_rgb` or `process_row_yuv`) on the bloom output IS row-pure. Parallelize THAT step across `orw[0..rtp-1]` using the same worker-pool dispatch pattern.

This gives most of the win on the bloom path without risking output differences.

### 2e. Initialize the worker pool unconditionally on first native call.
The current `init_worker_pool()` (line ~108) is gated by `active_workers > 1` inside the loop, which means the first chunk that needs threading pays the thread-creation cost. Move the init call to the top of `processImageNative`, gated only by `numCores > 1`. Idempotent (`if (g_pool.initialized) return;`) so it's safe.

### 2f. Bug to be aware of (also fix).
The existing dispatch hands tasks 1..N to workers and runs task 0 on the main thread (line ~397). When `active_workers == 1` it runs only task 0 on main, which is correct. Preserve this pattern in the new code.

**Acceptance criteria:**
- Build succeeds.
- A processed JPEG output, byte-compared against a build with this change disabled (use a `#define MULTITHREAD_LUT_PATH 1` toggle for the comparison), is bit-identical for at least three test cases:
  - LUT applied, no effects
  - LUT applied with vignette + grain
  - LUT applied with bloom + halation (verifies the bloom-path split)
- The `PERF:` line from Step 0 shows `row_loop` time approximately divided by `numCores` (allowing 20% overhead). On a 4-core camera, expect the row loop to drop to roughly 30–35% of its single-threaded time.
- Total wall-clock time on a half-res shot drops from ~14s to a meaningfully lower number — exactly how much depends on what fraction of the total was kernel work (Step 0 will tell us; expect the kernel to be 60–80% of the total).

**Risk:** Low. The kernels are row-pure (verified by reading `process_row_rgb`, `process_row_yuv`). The bloom/halation handling is the main place to be careful — that's why it's split.

**Estimated effort:** 4–8 hours of careful implementation + testing.

---

## Step 3 — If Step 1 found NEON, enable libjpeg-turbo SIMD

**Goal:** Free 2–4× on JPEG decode and encode if the silicon supports it.

**Only do this step if Step 1's `Features:` line in `/proc/cpuinfo` contained `neon`.**

**File:** `app/src/main/cpp/CMakeLists.txt`.

**Change:** Line 10, change `set(WITH_SIMD OFF CACHE BOOL "" FORCE)` to `set(WITH_SIMD ON CACHE BOOL "" FORCE)`. Update the comment to reflect verified NEON presence.

**Also:** In `app/build.gradle`, ensure the `cppFlags` include `-mfpu=neon` (or set per-module via target_compile_options). Currently the externalNativeBuild block (line 17–22) only sets `-std=c++11` and `-DCMAKE_BUILD_TYPE=Release`.

**Acceptance criteria:**
- Build succeeds (libjpeg-turbo's NEON assembly compiles for armeabi-v7a).
- Output JPEGs are byte-compared against the pre-NEON build for three test images. They should be bit-identical OR differ only in known SIMD-vs-scalar IDCT rounding (libjpeg-turbo's NEON IDCT is documented to be bit-identical to its scalar IDCT for the JDCT_IFAST method we use; verify).
- The `PERF:` line shows `decode_setup` and `encode_finish` times reduced.

**Risk:** Medium. If the assumption was wrong and only some bodies have NEON, the APK will crash with SIGILL on bodies without it. **Mitigation:** ship two APK variants (NEON and non-NEON), or add a runtime cpuinfo check at app startup that refuses to use NEON-built libjpeg if neon is absent. Cleanest: keep the non-NEON build as the default release for now, ship a separate `JPEGCAM-v2.02-NEON.apk` for users who confirmed their body has it, gather feedback before defaulting.

**Estimated effort:** 1–2 hours including the variant build setup.

---

## Step 4 — Bigger stdio buffers for SD card I/O

**Goal:** Eliminate per-MCU SD-card stalls during decode and encode. Modest but free.

**File:** `app/src/main/cpp/native-lib.cpp`, inside `processImageNative`.

**Change:** Immediately after `fopen(ifn, "rb")` and `fopen(ofn, "wb")` (line ~226), add:
```c
static char in_buf[262144];   // 256KB
static char out_buf[262144];
setvbuf(inf, in_buf, _IOFBF, sizeof(in_buf));
setvbuf(ouf, out_buf, _IOFBF, sizeof(out_buf));
```

Note: `static` to avoid stack pressure. Native heap can also work via `malloc` if we want them per-call; static is simpler and these don't need to be per-call.

**Acceptance criteria:**
- Build succeeds.
- Output bit-identical to before.
- `PERF:` line shows modest reduction in `decode_setup` and `encode_finish` (5–15% likely).

**Risk:** Very low. Pure stdio buffering; libjpeg-turbo uses stdio sources/destinations and benefits transparently.

**Estimated effort:** 15 minutes.

---

## Step 5 — Tune compile flags

**Goal:** Squeeze 5–15% out of the kernel via better instruction scheduling for the in-order Cortex-A5.

**File:** `app/src/main/cpp/CMakeLists.txt`.

**Change:** Line 21 currently sets `-O3` only on `native-lib`. Extend to:
```cmake
target_compile_options(native-lib PRIVATE
    -O3
    -funroll-loops
    -ffast-math
    -march=armv7-a
    -mtune=cortex-a5
    -mfpu=vfpv4
    -mfloat-abi=softfp
)
```

**Notes:**
- `-mtune=cortex-a5` lets the compiler schedule for the A5's 8-stage in-order pipeline instead of the default A8/A9 model.
- `-ffast-math` enables relaxed FP rules. Audit `process_kernel.h` first for any code that depends on exact float NaN/Inf semantics or relies on `==` comparisons on floats. If present, exclude `-ffast-math` from those translation units.
- `-mfpu=vfpv4` matches the A5's FPU. If Step 1 found NEON, change this to `-mfpu=neon-vfpv4`.
- `-mfloat-abi=softfp` matches Android's NDK r10 ABI. Do NOT use `-mfloat-abi=hard` — it would break ABI compatibility with the rest of Android.

**Acceptance criteria:**
- Build succeeds.
- Output bit-identical OR differing only at the LSB (within 1/255 per channel) for three test images. Check with `cmp -l` or a small Python script using PIL.
- `PERF:` line shows `row_loop` drop by 5–15%.

**Risk:** Low-medium. `-ffast-math` is the most aggressive flag; if any output difference is visible, try without it first.

**Estimated effort:** 30 minutes plus quality verification.

---

## Step 6 — Specialize the row kernel by feature combination (OPTIONAL)

**Goal:** Eliminate per-pixel branches inside `process_row_rgb` for features that are off. Expected: 10–25% additional kernel speedup.

**Why it matters:** The Cortex-A5 is an in-order 8-stage pipeline with no branch mispredict recovery via reorder buffers. Per-pixel branches like `if (vignette > 0)`, `if (chromeBlue > 0)`, `if (halation > 0)` are paid every pixel even when those features are off.

**File:** `app/src/main/cpp/process_kernel.h`, `process_row_rgb` function.

**Approach:** Generate two-three template specializations using a `template<bool kVignette, bool kChrome, bool kSat>` pattern, OR (simpler in C++11) generate by macro expansion:
```cpp
#define KERNEL_VARIANT_NAME(name) process_row_rgb_##name
#define KERNEL_VARIANT(name, has_vig, has_chrome, has_sat) \
    static inline void KERNEL_VARIANT_NAME(name)(...) { \
        // body with constant-folded branches \
    }
KERNEL_VARIANT(plain, 0, 0, 0)
KERNEL_VARIANT(vig,   1, 0, 0)
// etc.
```

Then dispatch ONCE at the top of the row chunk based on which features are active. The hot per-pixel loop has no branches for inactive features — they constant-fold away.

**Acceptance criteria:**
- Build succeeds.
- Output bit-identical to the unspecialized kernel (CRITICAL — this is the highest-risk step for visual difference).
- `PERF:` shows further `row_loop` reduction.

**Risk:** Medium. Easy to introduce a divergence between variants. Dispatch test matrix: cross-product of LUT × vignette × chrome × sat × grain, at minimum 8 variants tested for byte-identical output.

**Estimated effort:** 1–2 days. Skip unless Steps 0–5 didn't get the wall-clock under 5s.

---

## Verification commands

After each step, run on the user's macOS:
```bash
cd /Users/james-macbook/Documents/jpegcam
./gradlew assembleRelease
```
APK lands at `app/build/outputs/apk/release/JPEGCAM-v2.02.apk`. User installs to camera via existing PMCA flow.

For byte-comparison of output JPEGs, after the user pulls a processed file off the SD card:
```bash
cmp before.jpg after.jpg          # must report "differ" only at expected positions
shasum before.jpg after.jpg       # ideal: identical hashes
```

For pixel-level diff (when bit-identical isn't expected):
```python
from PIL import Image, ImageChops
a = Image.open("before.jpg")
b = Image.open("after.jpg")
diff = ImageChops.difference(a, b)
bbox = diff.getbbox()
print("Diff bbox:", bbox)
# Inspect max channel delta
import numpy as np
print("Max channel delta:", np.array(diff).max())
```
Acceptable: max delta ≤ 1 per channel from `-ffast-math` or SIMD IDCT rounding. Visible (>2) requires investigation.

---

## Order of execution

1. Step 0 (profile) — REQUIRED FIRST, ship debug build, get numbers.
2. Step 1 (cpuinfo dump) — ship together with Step 0; same risk, same release.
3. Step 2 (multi-thread LUT path) — biggest single win. Do this next regardless of profile findings.
4. Step 4 (stdio buffers) — quick free win, do alongside Step 2.
5. Step 5 (compile flags) — quick, low risk.
6. Step 3 (NEON) — only if Step 1 confirmed.
7. Step 6 (kernel specialization) — only if still slow after the above.

After Steps 0+1+2+4+5 we expect total time well under 5s on half-res. If Step 3 also applies, under 3s.

---

## What NOT to try

- Custom code on the SA-DSP. Sony-side firmware owns it. Brick risk, and the instruction-set work is partial.
- Hardware JPEG codec. Not exposed to PMCA Android sandbox.
- Replacing libjpeg-turbo with NanoJPEG / SAIL / Jpegli. Without their SIMD they don't beat turbo's portable C path; with SIMD they hit the same NEON question.
- DCT-domain LUT application. The LUT is nonlinear; this only works for linear color transforms.
- Lower scale_denom (e.g. 4 instead of 2). Visible quality loss.
- Switching to a non-IFAST DCT method. JDCT_IFAST is already the fastest.
- Writing to the cache dir for temp files. Internal storage is constrained; temp files belong on the SD card per the existing `.cam` handling pattern.
- Creating any file with a long filename. 8.3 only.
