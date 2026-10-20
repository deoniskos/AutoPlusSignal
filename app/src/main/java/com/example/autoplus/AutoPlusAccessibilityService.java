package com.example.autoplus;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class AutoPlusAccessibilityService extends AccessibilityService {

    private static final String SIGNAL_PACKAGE = "org.thoughtcrime.securesms";
    private static final String TARGET = "всі в укритті";

    private static final long REACTION_DELAY_MS = 5000L;
    private static final long SCAN_DEBOUNCE_MS = 180L;
    private static final long AFTER_LONG_PRESS_MS = 520L;
    private static final long AFTER_MORE_MS = 520L;
    private static final long RECENT_TTL_MS = 10 * 60 * 1000L;

    private static final Pattern REACTION_COUNTER =
            Pattern.compile("^[+＋➕]?\\s*\\d+$");

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private boolean actionInProgress = false;

    // fingerprint -> time reacted/scheduled
    private final LinkedHashMap<String, Long> recent = new LinkedHashMap<>();

    private final Runnable scanRunnable = this::scanForTarget;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null) {
            prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        }

        if (!prefs.getBoolean(MainActivity.KEY_ENABLED, false)) return;
        if (actionInProgress) return;
        if (event == null || event.getPackageName() == null) return;
        if (!SIGNAL_PACKAGE.contentEquals(event.getPackageName())) return;

        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        actionInProgress = false;
    }

    private void scanForTarget() {
        if (!automationEnabled() || actionInProgress) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        cleanupRecent();

        List<Candidate> candidates = new ArrayList<>();
        collectTargetCandidates(root, candidates);

        if (candidates.isEmpty()) return;

        candidates.sort((a, b) -> Integer.compare(b.centerY, a.centerY));

        for (Candidate candidate : candidates) {
            if (recent.containsKey(candidate.fingerprint)) continue;

            recent.put(candidate.fingerprint, System.currentTimeMillis());

            handler.postDelayed(
                    () -> beginReaction(candidate.fingerprint, candidate.centerY),
                    REACTION_DELAY_MS
            );
            break;
        }
    }

    private void beginReaction(String fingerprint, int originalY) {
        if (!automationEnabled() || actionInProgress) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        Candidate candidate = findCandidate(root, fingerprint, originalY);
        if (candidate == null) return;

        actionInProgress = true;

        boolean longClicked = candidate.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);

        if (longClicked) {
            handler.postDelayed(
                    () -> clickMoreThenPlus(candidate.centerY),
                    AFTER_LONG_PRESS_MS
            );
        } else {
            dispatchLongPress(candidate.centerX, candidate.centerY, () ->
                    handler.postDelayed(
                            () -> clickMoreThenPlus(candidate.centerY),
                            180L
                    )
            );
        }
    }

    private void clickMoreThenPlus(int messageY) {
        if (!automationEnabled()) {
            actionInProgress = false;
            return;
        }

        boolean clickedMore = clickMoreButton();

        if (!clickedMore) {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;

            int x = (int) (width * 0.81f);
            int y = messageY - (int) (height * 0.10f);
            y = clamp(y, (int) (height * 0.08f), (int) (height * 0.85f));

            tap(x, y);
        }

        handler.postDelayed(this::clickPlusInPicker, AFTER_MORE_MS);
    }

    private boolean clickMoreButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all);

        AccessibilityNodeInfo best = null;
        int bestX = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo node : all) {
            String label = nodeLabel(node);
            String low = label.toLowerCase(Locale.ROOT);

            boolean looksLikeMore =
                    label.equals("…") ||
                    label.equals("...") ||
                    label.equals("⋯") ||
                    label.equals("•••") ||
                    low.contains("більше реак") ||
                    low.contains("інші реак") ||
                    low.contains("more reaction");

            if (!looksLikeMore) continue;

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (r.centerX() > bestX) {
                best = node;
                bestX = r.centerX();
            }
        }

        if (best == null) return false;

        if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }

        Rect r = new Rect();
        best.getBoundsInScreen(r);
        tap(r.centerX(), r.centerY());
        return true;
    }

    private void clickPlusInPicker() {
        if (!automationEnabled()) {
            actionInProgress = false;
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            actionInProgress = false;
            return;
        }

        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all);

        Rect sectionBounds = null;

        for (AccessibilityNodeInfo node : all) {
            String label = nodeLabel(node).trim().toLowerCase(Locale.ROOT);
            if (label.equals("це повідомлення")) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                sectionBounds = r;
                break;
            }
        }

        AccessibilityNodeInfo plusNode = null;
        int bestY = Integer.MAX_VALUE;

        for (AccessibilityNodeInfo node : all) {
            String label = nodeLabel(node).trim();

            boolean exactPlus =
                    label.equals("+") ||
                    label.equals("＋") ||
                    label.equals("➕");

            if (!exactPlus) continue;
            if (REACTION_COUNTER.matcher(label).matches() && !label.equals("+")) continue;

            Rect r = new Rect();
            node.getBoundsInScreen(r);

            if (sectionBounds != null && r.centerY() <= sectionBounds.bottom) {
                continue;
            }

            if (r.centerY() < bestY) {
                plusNode = node;
                bestY = r.centerY();
            }
        }

        if (plusNode != null) {
            if (!plusNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Rect r = new Rect();
                plusNode.getBoundsInScreen(r);
                tap(r.centerX(), r.centerY());
            }
            finishReaction();
            return;
        }

        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;

        int x = (int) (width * 0.078f);
        int y;

        if (sectionBounds != null) {
            y = sectionBounds.bottom + (int) (height * 0.045f);
        } else {
            y = (int) (height * 0.67f);
        }

        tap(x, y);
        finishReaction();
    }

    private void finishReaction() {
        handler.postDelayed(() -> {
            actionInProgress = false;

            if (automationEnabled()) {
                handler.postDelayed(scanRunnable, 350L);
            }
        }, 500L);
    }

    private Candidate findCandidate(AccessibilityNodeInfo root, String fingerprint, int originalY) {
        List<Candidate> candidates = new ArrayList<>();
        collectTargetCandidates(root, candidates);

        Candidate nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (Candidate c : candidates) {
            if (fingerprint.equals(c.fingerprint)) return c;

            int distance = Math.abs(c.centerY - originalY);
            if (distance < nearestDistance) {
                nearest = c;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    private void collectTargetCandidates(AccessibilityNodeInfo node, List<Candidate> out) {
        if (node == null) return;

        if (matchesTarget(node)) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);

            AccessibilityNodeInfo bubble = chooseBubbleAncestor(node);
            String fingerprint = buildFingerprint(bubble);

            out.add(new Candidate(
                    node,
                    fingerprint,
                    r.centerX(),
                    r.centerY()
            ));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTargetCandidates(child, out);
            }
        }
    }

    private boolean matchesTarget(AccessibilityNodeInfo node) {
        String text = safe(node.getText());
        String desc = safe(node.getContentDescription());

        return normalize(text).equals(TARGET) || normalize(desc).equals(TARGET);
    }

    private String normalize(String input) {
        String s = safe(input).trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\s+", " ");
        s = s.replaceAll("[\\p{P}\\p{S}]+$", "");
        return s.trim();
    }

    private AccessibilityNodeInfo chooseBubbleAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo best = node;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        for (int i = 0; i < 4; i++) {
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;

            Rect r = new Rect();
            parent.getBoundsInScreen(r);

            if (r.width() > screenWidth * 0.92f || r.height() > screenHeight * 0.36f) {
                break;
            }

            best = parent;
            current = parent;
        }

        return best;
    }

    private String buildFingerprint(AccessibilityNodeInfo bubble) {
        List<String> labels = new ArrayList<>();
        collectLabels(bubble, labels, 0, 5);

        StringBuilder sb = new StringBuilder();

        for (String label : labels) {
            String trimmed = label.trim();
            if (trimmed.isEmpty()) continue;

            if (REACTION_COUNTER.matcher(trimmed).matches()) continue;

            if (sb.indexOf(trimmed + "\n") >= 0) continue;
            sb.append(trimmed).append('\n');
        }

        if (sb.length() == 0) sb.append(TARGET);

        return Integer.toHexString(sb.toString().hashCode());
    }

    private void collectLabels(
            AccessibilityNodeInfo node,
            List<String> out,
            int depth,
            int maxDepth
    ) {
        if (node == null || depth > maxDepth) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if (!TextUtils.isEmpty(text)) out.add(text.toString());
        if (!TextUtils.isEmpty(desc)) out.add(desc.toString());

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectLabels(child, out, depth + 1, maxDepth);
            }
        }
    }

    private void flatten(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) flatten(child, out);
        }
    }

    private String nodeLabel(AccessibilityNodeInfo node) {
        String text = safe(node.getText()).trim();
        String desc = safe(node.getContentDescription()).trim();

        if (!text.isEmpty() && !desc.isEmpty()) return text + " " + desc;
        if (!text.isEmpty()) return text;
        return desc;
    }

    private String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private boolean automationEnabled() {
        return prefs != null && prefs.getBoolean(MainActivity.KEY_ENABLED, false);
    }

    private void cleanupRecent() {
        long now = System.currentTimeMillis();

        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : recent.entrySet()) {
            if (now - entry.getValue() > RECENT_TTL_MS) {
                expired.add(entry.getKey());
            }
        }

        for (String key : expired) recent.remove(key);

        while (recent.size() > 120) {
            String first = recent.keySet().iterator().next();
            recent.remove(first);
        }
    }

    private void dispatchLongPress(int x, int y, Runnable after) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 780);

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        if (after != null) after.run();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        actionInProgress = false;
                    }
                },
                handler
        );
    }

    private void tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 70);

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        dispatchGesture(gesture, null, handler);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class Candidate {
        final AccessibilityNodeInfo node;
        final String fingerprint;
        final int centerX;
        final int centerY;

        Candidate(
                AccessibilityNodeInfo node,
                String fingerprint,
                int centerX,
                int centerY
        ) {
            this.node = node;
            this.fingerprint = fingerprint;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }
}
