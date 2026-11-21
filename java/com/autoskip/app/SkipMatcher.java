package com.autoskip.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 开屏广告“跳过/关闭”按钮识别器（v2）。
 *
 * 与 v1 的差异：
 *  - 不再用 findAccessibilityNodeInfosByText 全局检索文案（搜“关闭”会把整页按钮都捞进来），
 *    改成对当前窗口树做有界 BFS（深度/节点数双上限），逐节点打分；
 *  - 文案从“前缀匹配”改为“标签形态”判定，覆盖服务端下发的多种模板：
 *    “跳过5”“4 关闭”“5s 跳过”“跳过广告”“关闭广告”“skip” 等；
 *  - 用位置/尺寸/层级/可点击性做防误点约束，低置信的“关闭”必须落在屏幕角落。
 */
final class SkipMatcher {

    /** 命中后用于排除“跳过更新 / 跳过登录”这类非广告控件。 */
    private static final String[] BLOCK_WORDS = {
            "更新", "升级", "登录", "注册", "验证", "权限", "设置",
            "引导", "教程", "安装", "下载", "评分", "评价", "取消"
    };

    private static final int MAX_DEPTH = 14;
    private static final int MAX_NODES = 400;
    private static final int MAX_LABEL_LEN = 8;

    /** 高置信文案（跳过/skip/关闭广告）的命中阈值。 */
    private static final int SCORE_SKIP = 44;
    /** 低置信文案（只有“关闭”）的命中阈值，且必须落在角落。 */
    private static final int SCORE_CLOSE = 42;
    /** 低置信文案要求的最小角落加分。 */
    private static final int NEED_CORNER = 8;

    private SkipMatcher() {
    }

    /**
     * @return 最可能是开屏广告跳过/关闭按钮的节点；没有则返回 null。
     */
    static AccessibilityNodeInfo findSkipNode(AccessibilityNodeInfo root, int screenWidth, int screenHeight) {
        if (root == null) {
            return null;
        }
        Candidate best = null;
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(root, 0));
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            NodeDepth current = queue.poll();
            visited++;
            if (current.depth > 0) {
                Candidate c = evaluate(current.node, current.depth, screenWidth, screenHeight);
                if (c != null && (best == null || c.score > best.score)) {
                    best = c;
                }
            }
            if (current.depth >= MAX_DEPTH) {
                continue;
            }
            int children = current.node.getChildCount();
            for (int i = 0; i < children; i++) {
                AccessibilityNodeInfo child = current.node.getChild(i);
                if (child != null) {
                    queue.add(new NodeDepth(child, current.depth + 1));
                }
            }
        }
        if (best == null) {
            return null;
        }
        int need = best.labelScore >= 40 ? SCORE_SKIP : SCORE_CLOSE;
        if (best.labelScore < 40 && best.corner < NEED_CORNER) {
            return null;
        }
        return best.score >= need ? best.node : null;
    }

    private static Candidate evaluate(AccessibilityNodeInfo node, int depth,
                                      int screenWidth, int screenHeight) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return null;
        }
        String text = asText(node.getText());
        String desc = asText(node.getContentDescription());
        String viewId = node.getViewIdResourceName();

        int label = Math.max(labelScore(text), labelScore(desc));
        int idBonus = idScore(viewId);
        if (label == 0 && idBonus == 0) {
            return null;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return null;
        }

        int score = label > 0 ? label : 6;
        score += idBonus;

        if (node.isClickable() || hasClickableAncestor(node, 3)) {
            score += 10;
        }

        int corner = 0;
        if (screenWidth > 0 && screenHeight > 0) {
            int cx = bounds.centerX();
            int cy = bounds.centerY();
            if (cy < screenHeight * 0.30f && cx > screenWidth * 0.55f) {
                corner = 10; // 右上角：绝大多数开屏广告的“跳过”
            } else if (cy > screenHeight * 0.72f && cx > screenWidth * 0.50f) {
                corner = 8;  // 右下角：微博等模板（部分在底部半屏卡片上方，约 72%~96% 屏高）
            } else if (cy < screenHeight * 0.25f && cx < screenWidth * 0.45f) {
                corner = 8;  // 左上角：少数开屏广告模板
            }
            score += corner;

            long area = (long) bounds.width() * bounds.height();
            long screenArea = (long) screenWidth * screenHeight;
            if (screenArea > 0 && area <= screenArea / 8) {
                score += 4; // 按钮一般不大
            }
            if (bounds.height() > screenHeight / 12 || bounds.width() > screenWidth / 2) {
                score -= 8; // 排除整屏/半屏容器
            }
        }
        if (depth >= 3) {
            score += 2;
        }
        if (label < 40) {
            // 低置信的“关闭”只接受屏幕右上角最上方一条：正文/卡片里的“关闭”“×”一律不点
            if (corner != 10 || bounds.centerY() > screenHeight * 0.18f) {
                return null;
            }
        }

        Candidate c = new Candidate();
        c.node = node;
        c.score = score;
        c.labelScore = label;
        c.corner = corner;
        return c;
    }

    /**
     * @return 42 = 高置信（跳过/skip/关闭广告）；20~22 = 低置信（只有“关闭”）；0 = 不像。
     */
    private static int labelScore(String raw) {
        if (raw == null) {
            return 0;
        }
        String s = normalize(raw);
        if (s.isEmpty() || s.length() > MAX_LABEL_LEN) {
            return 0;
        }
        for (String blocked : BLOCK_WORDS) {
            if (s.contains(blocked)) {
                return 0;
            }
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("skip")) {
            return 42;
        }
        if (s.contains("跳过")) {
            // “跳过5”“跳过广告”“5跳过”“5s跳过” 都算
            return 42;
        }
        if (s.contains("关闭广告") || s.contains("关闭开屏")) {
            return 42;
        }
        if (s.equals("关闭")) {
            return 20;
        }
        // “4关闭”“关闭4” 这类倒计时 + 关闭的组合，长度很短
        if (s.length() <= 5 && s.endsWith("关闭")) {
            return 22;
        }
        if (s.length() <= 5 && s.startsWith("关闭")) {
            return 20;
        }
        return 0;
    }

    /**
     * 只看资源名本身（:id/ 之后的部分），避免包名把寻常控件误判成跳过按钮。
     */
    private static int idScore(String viewId) {
        if (viewId == null) {
            return 0;
        }
        int idx = viewId.indexOf(":id/");
        String name = (idx >= 0 ? viewId.substring(idx + 4) : viewId).toLowerCase(Locale.ROOT);
        if (name.contains("skip") || name.contains("jump")) {
            return 12;
        }
        if (name.contains("ad_close") || name.contains("splash_close") || name.contains("close_ad")) {
            return 10;
        }
        if (name.contains("count_down") || name.contains("countdown")) {
            return 8;
        }
        return 0;
    }

    private static boolean hasClickableAncestor(AccessibilityNodeInfo node, int maxDepth) {
        AccessibilityNodeInfo cur = node.getParent();
        for (int i = 0; i < maxDepth && cur != null; i++) {
            if (cur.isClickable() && cur.isEnabled()) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    /** 去掉空白与分隔符、全角转半角，便于统一判定。 */
    private static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == ' ' || ch == '\u3000' || ch == '\t' || ch == '\n' || ch == '|' || ch == '｜') {
                continue;
            }
            if (ch >= '０' && ch <= '９') {
                ch = (char) (ch - '０' + '0');
            }
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    private static String asText(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static final class NodeDepth {
        final AccessibilityNodeInfo node;
        final int depth;

        NodeDepth(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        int score;
        int labelScore;
        int corner;
    }
}
