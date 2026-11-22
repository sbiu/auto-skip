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
            "引导", "教程", "安装", "下载", "评分", "评价", "取消",
            "绑定", "跳过此", "全部", "暂不", "下次", "以后", "拒绝", "同意",
            "login", "signin", "signup", "update", "upgrade", "tutorial",
            "guide", "step", "setup", "verify", "bind", "account",
            "permission", "cancel", "decline", "agree"
    };

    private static final int MAX_DEPTH = 24;
    private static final int MAX_NODES = 1200;
    private static final int MAX_LABEL_LEN = 14;

    /** 高置信文案（跳过/skip/关闭广告/倒计时+关闭）的命中阈值。 */
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
            // 逆序入队（高 Z-order 浮层先于底层信息流）并跳过不可见子树（如离屏 ViewPager 分页），避免广告节点被挤出 MAX_NODES
            for (int i = children - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = current.node.getChild(i);
                if (child != null && child.isVisibleToUser()) {
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
        if (containsBlockedWord(text) || containsBlockedWord(desc)) {
            return null;
        }
        String viewId = node.getViewIdResourceName();

        int label = Math.max(labelScore(text), labelScore(desc));
        int idBonus = idScore(viewId);
        if (label == 0 && idBonus == 0) {
            return null;
        }
        if (label == 0 && (!isSplashIdOnlyLabel(text) || !isSplashIdOnlyLabel(desc))) {
            return null;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return null;
        }

        boolean clickable = node.isClickable() || hasClickableAncestor(node, 3);
        int score = label > 0 ? label : (24 + idBonus);
        if (label > 0) {
            score += idBonus;
        }
        if (clickable) {
            score += 10;
        }

        int corner = 0;
        boolean inTopRightEdge = false;
        boolean inBottomRightEdge = false;
        boolean inSplashIdBottomRight = false;
        if (screenWidth > 0 && screenHeight > 0) {
            int cx = bounds.centerX();
            int cy = bounds.centerY();
            if (cy < screenHeight * 0.30f && cx > screenWidth * 0.55f) {
                corner = 10; // 右上角：开屏广告最常见位置
                inTopRightEdge = cy < screenHeight * 0.25f;
            } else if (cy > screenHeight * 0.68f && cx > screenWidth * 0.52f) {
                // 右下角：微博 / B 站 / 第三方开屏聚合 SDK 常用位置，与右上角同等优先
                corner = (cy > screenHeight * 0.72f && cx > screenWidth * 0.55f) ? 10 : 8;
                inBottomRightEdge = cy > screenHeight * 0.84f && cx > screenWidth * 0.65f;
                inSplashIdBottomRight = cy > screenHeight * 0.76f && cx > screenWidth * 0.55f;
            } else if (cy < screenHeight * 0.25f && cx < screenWidth * 0.45f) {
                corner = 8;  // 左上角：少数开屏广告模板
            } else if (cy > screenHeight * 0.78f && cx < screenWidth * 0.42f) {
                corner = 6;  // 左下角：少数横屏或聚合 SDK 模板
            }
            score += corner;

            long area = (long) bounds.width() * bounds.height();
            long screenArea = (long) screenWidth * screenHeight;
            if (screenArea > 0 && area <= screenArea / 8) {
                score += 4; // 按钮一般不大
            }
            if (bounds.height() > screenHeight / 8 || bounds.width() > screenWidth * 0.55f) {
                score -= 8; // 排除整屏/半屏容器
            }
        }
        if (depth >= 3) {
            score += 2;
        }
        if (label == 0) {
            // 无“跳过/关闭”文案、仅凭明确开屏 viewId 识别的控件，必须落在右上角或右下角且尺寸紧凑
            if (screenWidth <= 0 || screenHeight <= 0) {
                return null;
            }
            if (!inTopRightEdge && !inSplashIdBottomRight) {
                return null;
            }
            if (bounds.width() > screenWidth * 0.40f || bounds.height() > screenHeight * 0.10f) {
                return null;
            }
            if (!clickable && idBonus < 12) {
                return null;
            }
        } else if (label < 40) {
            // 低置信的纯“关闭/点击关闭”只接受右上角顶部或右下角底边紧贴角落的小按钮，避免误点信息流卡片关闭按钮与弹窗居中关闭按钮
            if (screenWidth <= 0 || screenHeight <= 0) {
                return null;
            }
            if (!inTopRightEdge && !inBottomRightEdge) {
                return null;
            }
            if (!clickable || bounds.width() > screenWidth * 0.35f || bounds.height() > screenHeight * 0.09f) {
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
     * @return >=40 高置信（跳过/skip/关闭广告/倒计时关闭）；20~22 低置信（只有“关闭”）；0 不像。
     */
    private static int labelScore(String raw) {
        if (raw == null) {
            return 0;
        }
        String s = normalize(raw);
        if (s.isEmpty() || s.length() > MAX_LABEL_LEN) {
            return 0;
        }
        if (containsNormalizedBlocked(s)) {
            return 0;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("skip")) {
            return 42;
        }
        if (s.contains("跳过")) {
            // “跳过5”“跳过广告”“5跳过”“5s跳过”“3s后可跳过” 都算
            return 42;
        }
        if (s.contains("关闭广告") || s.contains("关闭开屏")) {
            return 42;
        }
        // “4关闭”“关闭4”“5s关闭”“3秒关闭” 这类带数字倒计时 + 关闭的组合，右上角/右下角均高置信命中
        if (s.length() <= 7 && hasDigit(s) && s.contains("关闭")) {
            return 40;
        }
        if (s.equals("关闭")) {
            return 20;
        }
        if (s.length() <= 5 && (s.endsWith("关闭") || s.startsWith("关闭"))) {
            return 22;
        }
        return 0;
    }

    private static boolean containsBlockedWord(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String s = normalize(raw);
        return !s.isEmpty() && containsNormalizedBlocked(s);
    }

    private static boolean containsNormalizedBlocked(String normalized) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCK_WORDS) {
            if (lower.contains(blocked)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSplashIdOnlyLabel(String raw) {
        if (raw == null) {
            return true;
        }
        String s = normalize(raw);
        if (s.isEmpty()) {
            return true;
        }
        if (s.equals("×") || s.equals("✕") || s.equals("✖") || s.equals("╳")) {
            return true;
        }
        if (s.length() > 4) {
            return false;
        }
        String body = s;
        if (body.endsWith("s") || body.endsWith("S") || body.endsWith("秒")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isEmpty() || body.length() > 2) {
            return false;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
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
        if (name.contains("ad_close") || name.contains("splash_close")
                || name.contains("close_ad") || name.contains("close_splash")) {
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
            if (ch == ' ' || ch == '\u3000' || ch == '\t' || ch == '\n' || ch == '\r'
                    || ch == '|' || ch == '｜' || ch == '·' || ch == '•'
                    || ch == '(' || ch == ')' || ch == '（' || ch == '）'
                    || ch == '>' || ch == '<' || ch == '›' || ch == '»' || ch == '/') {
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
