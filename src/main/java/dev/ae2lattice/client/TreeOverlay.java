package dev.ae2lattice.client;

import java.lang.ref.WeakReference;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatusMenu;
import dev.ae2lattice.LatticeConfig;
import dev.ae2lattice.client.TreeModel.Edge;
import dev.ae2lattice.client.TreeModel.Node;
import dev.ae2lattice.client.TreeModel.Status;
import dev.ae2lattice.net.SnapshotData;
import dev.ae2lattice.net.SnapshotData.MachineInfo;
import dev.ae2lattice.net.SnapshotData.MachineState;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Draws the Lattice tree on top of AE2's crafting status screen. AE2's screen stays open underneath,
 * so its menu keeps syncing and all of its own behaviour is untouched.
 */
public final class TreeOverlay {
    private static final int C_PANEL = 0xFFC6C6C6, C_LIGHT = 0xFFFFFFFF, C_DARK = 0xFF373737, C_INNER = 0xFFDCDCDC;
    private static final int C_CRAFT = 0xFFA3C795, C_SCHED = 0xFFE8E4C5, C_AVAIL = 0xFFDCDCDC, C_DONE = 0xFFC9C9C9,
            C_STALL = 0xFFE9A49C, C_CRIT = 0xFF00B4D8, C_TEXT = 0xFF303030, C_QUEUED = 0xFFD5DDB8;

    private static boolean treeMode;
    private static WeakReference<Screen> bound = new WeakReference<>(null);

    // camera (world units = GUI pixels at zoom 1)
    private static float camX, camY, zoom = 1f;
    private static boolean needFit = true;
    private static boolean dragging, dragMoved;

    // layout rects, recomputed each frame
    private static int px0, py0, px1, py1, gx0, gy0, gx1, gy1, sideX1;
    private static final List<Hit> hits = new ArrayList<>();

    private record Hit(int x0, int y0, int x1, int y1, Runnable click, @Nullable List<Component> tooltip,
            @Nullable List<com.mojang.datafixers.util.Either<net.minecraft.network.chat.FormattedText,
                    net.minecraft.world.inventory.tooltip.TooltipComponent>> rich) {
        Hit(int x0, int y0, int x1, int y1, Runnable click, @Nullable List<Component> tooltip) {
            this(x0, y0, x1, y1, click, tooltip, null);
        }

        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private static final class Flyer {
        final AEKey key;
        final Edge edge;
        final long start;
        final long dur;
        final boolean pop;
        final Node node;

        Flyer(AEKey key, @Nullable Edge edge, @Nullable Node node, long start, long dur, boolean pop) {
            this.key = key;
            this.edge = edge;
            this.node = node;
            this.start = start;
            this.dur = dur;
            this.pop = pop;
        }
    }

    private static final List<Flyer> flyers = new ArrayList<>();

    // ============================================================================================
    // screen lifecycle

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post e) {
        if (!(e.getScreen() instanceof CraftingCPUScreen<?> screen)) return;
        if (bound.get() != screen) {
            bound = new WeakReference<>(screen);
            treeMode = LatticeConfig.shouldOpenTree();
            needFit = true;
            flyers.clear();
        }
        ClientState.setSubscribed(treeMode);

        int bx = screen.getGuiLeft() + screen.getXSize() - 46;
        int by = screen.getGuiTop() - 16;
        if (by < 2) {
            bx = screen.width - 50;
            by = 2;
        }
        e.addListener(Button.builder(Component.literal("Tree"), b -> setTreeMode(true))
                .bounds(bx, by, 44, 14)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal("Show the Lattice crafting tree")))
                .build());
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing e) {
        if (e.getScreen() instanceof CraftingCPUScreen<?> && e.getScreen() == bound.get()) {
            ClientState.setSubscribed(false);
        }
    }

    private static void setTreeMode(boolean on) {
        treeMode = on;
        LatticeConfig.rememberView(on);
        ClientState.setSubscribed(on);
        if (on) needFit = true;
        flyers.clear();
    }

    private static boolean active(Screen s) {
        return treeMode && s instanceof CraftingCPUScreen<?> && s == bound.get();
    }

    /** True while the tree view is drawn over this screen (used by recipe-viewer integrations). */
    public static boolean isCovering(Screen s) {
        return active(s);
    }

    // ---- job-finished celebration (only when AE2 reports success while you're watching)
    private static final long CELEBRATE_MS = 1800;
    private static long celebratedId;
    private static long celebrateStart = Long.MIN_VALUE / 2;
    @Nullable
    private static SnapshotData.Finished celebrating;
    private static final List<float[]> confetti = new ArrayList<>(); // x, y, vx, vy, color, spin

    private static void maybeCelebrate(SnapshotData.Finished f) {
        if (f.id() == celebratedId) return;
        celebratedId = f.id();
        // only if it just happened (not when opening the screen on a job that finished minutes ago)
        if (!f.success() || f.ageTicks() > 60 || !LatticeConfig.CELEBRATE.get()) return;
        celebrating = f;
        celebrateStart = Util.getMillis();
        confetti.clear();
        var rnd = ThreadLocalRandom.current();
        int[] colors = { 0xFF00B4D8, 0xFFFFD54F, 0xFF66BB6A, 0xFFEF5350, 0xFFAB47BC, 0xFFFFFFFF };
        for (int i = 0; i < 60; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, sp = 60 + rnd.nextDouble() * 140;
            confetti.add(new float[] { 0, 0, (float) (Math.cos(a) * sp), (float) (Math.sin(a) * sp - 120),
                    colors[rnd.nextInt(colors.length)], rnd.nextFloat() * 6 });
        }
        if (LatticeConfig.CELEBRATE_SOUND.get()) {
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                    .forUI(net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.5f));
        }
    }

    private static void drawCelebration(GuiGraphics g, Font font, SnapshotData.Finished f, long now) {
        float t = (now - celebrateStart) / (float) CELEBRATE_MS; // 0..1
        int cx = (gx0 + gx1) / 2, cy = (gy0 + gy1) / 2 - 10;
        float pop = t < 0.15f ? t / 0.15f : 1f;                 // grow in
        float fade = t > 0.8f ? 1 - (t - 0.8f) / 0.2f : 1f;     // fade out at the end
        float size = 64 * (0.6f + 0.4f * pop);
        float angle = (now - celebrateStart) * 0.25f;

        // confetti
        float secs = (now - celebrateStart) / 1000f;
        for (var p : confetti) {
            float x = cx + p[2] * secs, y = cy + p[3] * secs + 220 * secs * secs;
            int col = ((int) (fade * 255) << 24) | ((int) p[4] & 0xFFFFFF);
            int s = 2 + (int) (Math.abs(Math.sin(secs * 6 + p[5])) * 2);
            g.fill((int) x, (int) y, (int) x + s, (int) y + 2, col);
        }

        // the finished item, spinning in 3D
        var key = f.output().what();
        if (key instanceof appeng.api.stacks.AEItemKey ik) {
            renderSpinningItem(g, ik.toStack(), cx, cy, size, angle);
        } else {
            var pose = g.pose();
            pose.pushPose();
            pose.translate(cx - size / 2, cy - size / 2, 150);
            pose.scale(size / 16f, size / 16f, 1);
            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, 0, 0, key);
            pose.popPose();
        }

        int alpha = Math.max(4, (int) (fade * 255)) << 24;
        String line1 = "✔ Crafted " + fmt(key, f.output().amount()) + "× " + key.getDisplayName().getString();
        String line2 = "in " + fmtDuration(TimeUnit.NANOSECONDS.toMillis(f.elapsedNanos()));
        g.drawString(font, line1, cx - font.width(line1) / 2, cy + (int) size / 2 + 10, alpha | 0x2E7D32, false);
        g.drawString(font, line2, cx - font.width(line2) / 2, cy + (int) size / 2 + 22, alpha | 0x555555, false);
    }

    private static void renderSpinningItem(GuiGraphics g, net.minecraft.world.item.ItemStack stack, int cx, int cy,
            float size, float angleDeg) {
        var mc = Minecraft.getInstance();
        var model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 150);
        pose.scale(size, -size, size);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(angleDeg));
        boolean flat = !model.usesBlockLight();
        if (flat) com.mojang.blaze3d.platform.Lighting.setupForFlatItems();
        mc.getItemRenderer().render(stack, net.minecraft.world.item.ItemDisplayContext.GUI, false, pose,
                g.bufferSource(), 15728880, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, model);
        g.flush();
        if (flat) com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
        pose.popPose();
    }

    /** Calm summary card after a job: slowly spinning item, time, steps, machines used (click = highlight). */
    @Nullable
    private static List<Component> drawSummary(GuiGraphics g, Font font, SnapshotData.Finished f, long now, int mx, int my) {
        var key = f.output().what();
        String name = key.getDisplayName().getString();
        long total = f.output().amount(), done = total - f.remaining();
        String title = f.success() ? "✔ Crafted " + fmt(key, total) + "× " + name
                : "✖ Cancelled: " + fmt(key, done) + " / " + fmt(key, total) + " " + name;
        String[][] rows = {
                { "Time", fmtDuration(TimeUnit.NANOSECONDS.toMillis(f.elapsedNanos())) },
                { "Steps", String.valueOf(f.steps()) },
                { "Machines used", String.valueOf(f.machines().size()) } };
        int labelW = 0, valueW = 0;
        for (var r : rows) { labelW = Math.max(labelW, font.width(r[0])); valueW = Math.max(valueW, font.width(r[1])); }
        int iconArea = 56;
        int machinesW = Math.min(12, f.machines().size()) * 20;
        int textW = Math.max(font.width(title), Math.max(labelW + 10 + valueW, machinesW));
        int w = iconArea + textW + 22, h = 14 + 12 + rows.length * 11 + (f.machines().isEmpty() ? 0 : 24) + 10;
        int x = (gx0 + gx1 - w) / 2, y = (gy0 + gy1 - h) / 2 - 6;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        bevel(g, x, y, x + w, y + h, C_PANEL, C_LIGHT, C_DARK);

        var pose = g.pose();
        int ix = x + 8 + iconArea / 2, iy = y + h / 2;
        if (key instanceof appeng.api.stacks.AEItemKey ik) {
            float angle = f.success() ? now * 0.045f : 30f; // one turn every 8 s; cancelled stands still
            renderSpinningItem(g, ik.toStack(), ix, iy, 36, angle);
        } else {
            pose.pushPose();
            pose.translate(ix - 16, iy - 16, 150);
            pose.scale(2, 2, 1);
            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, 0, 0, key);
            pose.popPose();
        }

        int tx = x + 12 + iconArea, ty = y + 10;
        g.drawString(font, title, tx, ty, f.success() ? 0xFF2E7D32 : 0xFF8B0000, false);
        ty += 14;
        for (var r : rows) {
            g.drawString(font, r[0], tx, ty, 0xFF707070, false);
            g.drawString(font, r[1], tx + labelW + 10, ty, 0xFF303030, false);
            ty += 11;
        }
        List<Component> tip = null;
        if (!f.machines().isEmpty()) {
            int sx = tx;
            ty += 3;
            for (int i = 0; i < Math.min(12, f.machines().size()); i++) {
                var m = f.machines().get(i);
                g.fill(sx, ty, sx + 18, ty + 18, 0xFF373737);
                g.fill(sx + 1, ty + 1, sx + 18, ty + 18, 0xFFFFFFFF);
                g.fill(sx + 1, ty + 1, sx + 17, ty + 17, 0xFF8B8B8B);
                g.renderItem(m.icon(), sx + 1, ty + 1);
                boolean hov = mx >= sx && mx < sx + 18 && my >= ty && my < ty + 18;
                if (hov) {
                    g.fill(sx + 1, ty + 1, sx + 17, ty + 17, 0x60FFFFFF);
                    tip = List.of(m.name().copy().withStyle(ChatFormatting.WHITE),
                            Component.literal(m.pos().getX() + " " + m.pos().getY() + " " + m.pos().getZ())
                                    .withStyle(ChatFormatting.GRAY),
                            Component.literal("Click to highlight").withStyle(ChatFormatting.DARK_GRAY));
                }
                hits.add(new Hit(sx, ty, sx + 18, ty + 18,
                        () -> highlightAt(m.pos(), m.dimension(), m.name(), Screen.hasShiftDown()), null));
                sx += 20;
            }
        }
        String hint = "This CPU is free again · start a new craft or pick another CPU";
        g.drawString(font, hint, (gx0 + gx1 - font.width(hint)) / 2, y + h + 8, 0xFF707070, false);
        return tip;
    }

    private static String finishedSummary(SnapshotData.Finished f) {
        var key = f.output().what();
        String name = key.getDisplayName().getString();
        if (f.success()) {
            return "✔ Last job: crafted " + fmt(key, f.output().amount()) + "× " + name + " in "
                    + fmtDuration(TimeUnit.NANOSECONDS.toMillis(f.elapsedNanos()));
        }
        long done = f.output().amount() - f.remaining();
        return "✖ Last job cancelled: " + fmt(key, done) + " / " + fmt(key, f.output().amount()) + " " + name;
    }

    static void onSnapshot(SnapshotData d, boolean newJob) {
        if (!d.hasJob && d.finished != null && treeMode) maybeCelebrate(d.finished);
        if (newJob) {
            needFit = true;
            flyers.clear();
        }
        var model = ClientState.MODEL;
        if (model.layoutChanged && needFit) model.layoutChanged = false;
        if (!LatticeConfig.ANIMATIONS.get() || !treeMode) return;
        long now = Util.getMillis();
        for (var ev : d.events) {
            Node n = model.byPattern.get(ev.patternId());
            if (n == null) continue;
            if (ev.push()) {
                // AE2 actually sent ingredients to this step's machine: each ingredient travels along its line.
                // This is the only thing that moves - one real transfer, one animation, no random delay.
                int perEdge = (int) Math.min(3, Math.max(1, ev.amount()));
                for (var edge : n.in) {
                    for (int i = 0; i < perEdge && flyers.size() < 120; i++) {
                        flyers.add(new Flyer(edge.key, edge, null, now + i * 70L, 300, false));
                    }
                }
            } else if (ev.key() != null) {
                // A machine handed its output back to AE2: just a short flash on that step.
                n.pulseUntil = now + 300;
            }
        }
    }

    // ============================================================================================
    // rendering

    /** LOWEST priority = draw after other mods' screen overlays (FTB sidebar, JEI list, ...), then cover them. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRender(ScreenEvent.Render.Post e) {
        if (!active(e.getScreen())) return;
        var g = e.getGuiGraphics();
        g.flush();
        // forget everything drawn so far depth-wise, so nothing underneath can poke through the tree view
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        render(e.getScreen(), g, e.getMouseX(), e.getMouseY());
    }

    private static void render(Screen screen, GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        var font = mc.font;
        var model = ClientState.MODEL;
        long now = Util.getMillis();
        hits.clear();

        var pose = g.pose();
        pose.pushPose();
        pose.translate(0, 0, 400);

        int w = screen.width, h = screen.height;
        g.fill(0, 0, w, h, 0xC0101418);
        px0 = 6;
        py0 = 6;
        px1 = w - 6;
        py1 = h - 6;
        // Like AE2: a separate "Crafting CPUs" window on the left (terminal status only), main window on the right
        CraftingStatusMenu cpuMenu = screen instanceof CraftingCPUScreen<?> cs0
                && cs0.getMenu() instanceof CraftingStatusMenu sm0 ? sm0 : null;
        int mainX0 = px0;
        if (cpuMenu != null) {
            int lw = 100;
            g.fill(px0 - 1, py0 - 1, px0 + lw + 1, py1 + 1, 0xFF000000);
            bevel(g, px0, py0, px0 + lw, py1, C_PANEL, C_LIGHT, C_DARK);
            mainX0 = px0 + lw + 4;
        }
        g.fill(mainX0 - 1, py0 - 1, px1 + 1, py1 + 1, 0xFF000000);
        bevel(g, mainX0, py0, px1, py1, C_PANEL, C_LIGHT, C_DARK);

        // ---- header (main window)
        Component title = title(model);
        g.drawString(font, title, mainX0 + 8, py0 + 7, 0xFF3F3F3F, false);
        int bx = px1 - 8;
        bx = headerButton(g, font, bx, "Grid", mx, my, () -> setTreeMode(false),
                List.of(Component.literal("Back to AE2's grid view")));
        bx = headerButton(g, font, bx, "Fit", mx, my, () -> needFit = true,
                List.of(Component.literal("Fit the whole tree in view (F)")));
        if (screen instanceof CraftingCPUScreen<?> cs && model.hasJob) {
            CraftingCPUMenu menu = (CraftingCPUMenu) cs.getMenu();
            headerButton(g, font, bx - 6, "Cancel", mx, my, menu::cancelCrafting,
                    List.of(Component.literal("Cancel this crafting job (same as AE2's Cancel)")
                            .withStyle(ChatFormatting.RED)));
        }

        // ---- CPU list (terminal crafting status only)
        if (cpuMenu != null) drawAeCpuList(g, font, cpuMenu, px0 + 7, py0 + 7, py1 - 7, mx, my);
        sideX1 = mainX0 + 6;

        // ---- graph viewport
        gx0 = sideX1;
        gy0 = py0 + 22;
        gx1 = px1 - 6;
        gy1 = py1 - 18;
        bevel(g, gx0 - 1, gy0 - 1, gx1 + 1, gy1 + 1, C_INNER, C_DARK, C_LIGHT);
        drawDots(g);

        if (needFit && model.hasJob && !model.nodes.isEmpty()) fit(model);

        List<Component> tooltip = null;
        hoverPanel = null;
        g.enableScissor(gx0, gy0, gx1, gy1);
        if (!ClientState.serverHasLattice()) {
            centered(g, font, "Lattice isn't installed on this server.", 0xFF8B0000);
            centered2(g, font, "The tree needs the mod on both server and client.");
        } else if (!model.hasJob && celebrating != null && now - celebrateStart < CELEBRATE_MS) {
            drawCelebration(g, font, celebrating, now);
        } else if (!model.hasJob && ClientState.lastFinished != null) {
            tooltip = drawSummary(g, font, ClientState.lastFinished, now, mx, my);
        } else if (!model.hasJob || model.nodes.isEmpty()) {
            if (!ClientState.receivedSinceSubscribe) {
                boolean slow = now - ClientState.subscribedAtMs > 3000;
                centered(g, font, "Waiting for the server…", 0xFF555555);
                if (slow) centered2(g, font, "No data received yet. (If this stays, please send logs/latest.log)");
            } else if (model.hasJob) {
                centered(g, font, "The job has no steps to show yet…", 0xFF555555);
            } else if (ClientState.lastEmptyReason == SnapshotData.EMPTY_NO_CPU) {
                centered(g, font, "No crafting CPU selected.", 0xFF555555);
            } else {
                centered(g, font, "This CPU isn't crafting anything right now.", 0xFF555555);
            }
        } else {
            tooltip = drawGraph(g, font, model, mx, my, now);
        }
        g.disableScissor();

        // ---- legend
        int lx = gx0;
        int ly = py1 - 13;
        lx = legend(g, font, lx, ly, C_CRAFT, "In machine");
        lx = legend(g, font, lx, ly, C_QUEUED, "Queued");
        lx = legend(g, font, lx, ly, C_SCHED, "To do");
        lx = legend(g, font, lx, ly, C_AVAIL, "Ready in CPU");
        legend(g, font, lx, ly, C_STALL, "Machine problem");
        String hint = "Drag · Scroll · Click machine";
        g.drawString(font, hint, px1 - 8 - font.width(hint), ly, 0xFF606060, false);

        // ---- tooltips (header/sidebar hits first, then graph)
        List<com.mojang.datafixers.util.Either<net.minecraft.network.chat.FormattedText,
                net.minecraft.world.inventory.tooltip.TooltipComponent>> rich = null;
        for (var hit : hits) {
            if (hit.contains(mx, my) && (hit.tooltip != null || hit.rich != null)) {
                tooltip = hit.tooltip;
                rich = hit.rich;
                break;
            }
        }
        if (rich != null) {
            g.renderComponentTooltipFromElements(font, rich, mx, my, ItemStack.EMPTY);
        } else if (hoverPanel != null) {
            drawMachinePanel(g, font, hoverPanel, mx, my, w, h);
        } else if (tooltip != null) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (var c : tooltip) lines.add(c.getVisualOrderText());
            g.renderTooltip(font, lines, mx, my);
        }
        pose.popPose();
    }

    private static Component title(TreeModel model) {
        var t = Component.literal("Crafting Status");
        var d = model.last;
        if (model.hasJob && d != null) {
            long now = Util.getMillis();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(d.elapsedNanos) + (now - model.lastUpdateMs);
            t.append(" · running " + fmtDuration(elapsedMs));
            if (model.anyProblem()) {
                t.append(Component.literal("  ⚠ machine problem").withStyle(ChatFormatting.DARK_RED));
            }
        }
        return t;
    }

    private static List<Component> drawGraph(GuiGraphics g, Font font, TreeModel model, int mx, int my, long now) {
        var pose = g.pose();
        double wmx = (mx - gx0) / zoom + camX;
        double wmy = (my - gy0) / zoom + camY;
        boolean mouseInGraph = mx >= gx0 && mx < gx1 && my >= gy0 && my < gy1;
        List<Component> tooltip = null;

        pose.pushPose();
        pose.translate(gx0 - camX * zoom, gy0 - camY * zoom, 0);
        pose.scale(zoom, zoom, 1);

        // edges
        for (var e : model.edges) drawEdge(g, font, model, e, now);

        // nodes
        for (var n : model.nodes) {
            var tt = drawNode(g, font, model, n, now, wmx, wmy, mouseInGraph);
            if (tt != null) tooltip = tt;
        }

        // flyers
        drawFlyers(g, now);
        pose.popPose();
        return tooltip;
    }

    /**
     * Elbow route from the source's right side to the target's left side. Every edge gets its own exit point on the
     * source and its own entry point on the target (spread over the box height), so no two lines share a segment.
     */
    private static int[][] edgePath(Edge e) {
        int H = TreeModel.NODE_H;
        // ports ordered by where the other card sits (top card -> top port), so neighbouring lines never cross
        int outIdx = 0, inIdx = 0;
        for (var o : e.from.out) if (o != e && (o.to.y < e.to.y || (o.to.y == e.to.y && o.to.x < e.to.x))) outIdx++;
        for (var o : e.to.in) if (o != e && (o.from.y < e.from.y || (o.from.y == e.from.y && o.from.x < e.from.x))) inIdx++;
        int outN = Math.max(1, e.from.out.size()), inN = Math.max(1, e.to.in.size());
        int x1 = (int) e.from.x + TreeModel.NODE_W, y1 = (int) e.from.y + H * (outIdx + 1) / (outN + 1);
        int x2 = (int) e.to.x, y2 = (int) e.to.y + H * (inIdx + 1) / (inN + 1);
        // vertical lane: sources above the card nest top-down, sources below nest bottom-up
        // (outermost source gets the lane closest to the card) -> lines into one card never cross
        float ty = e.to.y + H / 2f, sy = e.from.y + H / 2f;
        boolean above = sy < ty;
        int lane = 0;
        for (var o : e.to.in) {
            if (o == e) continue;
            float oy = o.from.y + H / 2f;
            if ((oy < ty) != above) continue;
            if (above ? oy < sy : oy > sy) lane++;
        }
        int xm = x2 - 8 - lane * 4;
        return new int[][] { { x1, y1 }, { xm, y1 }, { xm, y2 }, { x2, y2 } };
    }

    private static void drawEdge(GuiGraphics g, Font font, TreeModel model, Edge e, long now) {
        var p = edgePath(e);
        long need = model.remainingNeed(e);
        int col = model.edgeLive(e) ? 0xFF5A5A5A : 0x66808080;
        for (int i = 0; i < 3; i++) line(g, p[i][0], p[i][1], p[i + 1][0], p[i + 1][1], col);
        if (need > 0) {
            // label sits on this edge's own first segment, right after the source - never shared with another edge
            String s = e.key.formatAmount(need, AmountFormat.SLOT) + " needed";
            float scale = 0.55f;
            float room = p[1][0] - p[0][0] - 6;
            if (font.width(s) * scale > room) s = e.key.formatAmount(need, AmountFormat.SLOT) + "×";
            var pose = g.pose();
            pose.pushPose();
            pose.translate(p[0][0] + 3, p[0][1] - 6, 0);
            pose.scale(scale, scale, 1);
            g.drawString(font, s, 0, 0, 0xFF303030, false);
            pose.popPose();
        }
    }

    @Nullable
    private static List<Component> drawNode(GuiGraphics g, Font font, TreeModel model, Node n, long now,
            double wmx, double wmy, boolean mouseInGraph) {
        var mc = Minecraft.getInstance();
        var pose = g.pose();
        Status st = model.status(n);
        var s = model.state(n);
        var ks = model.keys.get(n.key);
        int x = Math.round(n.x), y = Math.round(n.y);
        if (st == Status.STALLED) x += (int) Math.round(Math.sin(now / 35.0));
        int W = TreeModel.NODE_W, H = TreeModel.NODE_H;

        int bg = switch (st) {
            case CRAFTING -> C_CRAFT;
            case QUEUED -> C_QUEUED;
            case SCHEDULED -> C_SCHED;
            case STALLED -> C_STALL;
            case AVAILABLE -> C_AVAIL;
            case DONE -> C_DONE;
        };
        int border = st == Status.STALLED ? 0xFF990000 : n == model.root ? 0xFF1E1E1E : 0xFF555555;
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, border);
        if (n == model.root) g.fill(x - 2, y - 2, x + W + 2, y + H + 2, border);
        g.fill(x, y, x + W, y + H, bg);
        if (now < n.pulseUntil) {
            int a = (int) (0x90 * (n.pulseUntil - now) / 300.0);
            g.fill(x, y, x + W, y + H, (a << 24) | 0xFFFFFF);
        }

        AEKeyRendering.drawInGui(mc, g, x + 4, y + 7, n.key);

        String name = n.key.getDisplayName().getString();
        name = font.plainSubstrByWidth(name, W - 26);
        g.drawString(font, name, x + 23, y + 4, st == Status.DONE ? 0xFF707070 : C_TEXT, false);

        List<String> lines = new ArrayList<>(3);
        if (n == model.root && model.last != null && model.last.finalOutput != null) {
            long total = model.last.finalOutput.amount();
            lines.add("Done " + fmt(n.key, total - model.last.remainingAmount) + "/" + fmt(n.key, total));
        } else if (ks != null && ks.stored() > 0) {
            lines.add(fmt(n.key, ks.stored()) + " ready");
        }
        long inMachines = model.inMachines(n);
        var problem = model.hasProblem(s) ? s.problem() : null;
        if (problem != null) lines.add("§4" + problemText(problem));
        else if (inMachines < 0) lines.add("§6In machine: unknown");
        else if (inMachines > 0 && st == Status.QUEUED) lines.add("§6" + fmt(n.key, inMachines) + " queued");
        else if (inMachines > 0) lines.add("§2" + fmt(n.key, inMachines) + " in machine");
        // Measured fact for machines we can't read: how long since anything came back.
        if (problem == null && s != null && s.inMachines() > 0 && s.progressing() == null
                && s.sinceOutputTicks() >= 600) {
            lines.add("§6No output for " + fmtDuration(s.sinceOutputTicks() * 50L));
        }
        long pending = model.pendingOutput(n);
        if (pending > 0) lines.add("§6" + fmt(n.key, pending) + " to do");
        if (lines.isEmpty()) {
            if (!n.isLeaf()) lines.add("§8✔ Done");
            // Nothing of this item is in the CPU, in machines or scheduled. Say what that actually means:
            else if (model.consumersHaveRunsLeft(n)) lines.add("§6Waiting for more"); // needed, but none here yet
            else if (model.consumersStillWorking(n)) lines.add("§8✔ All handed to next step");
            else lines.add("§8✔ All used");
        }
        pose.pushPose();
        pose.translate(x + 23, y + 14, 0);
        pose.scale(0.62f, 0.62f, 1);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.drawString(font, lines.get(i), 0, i * 10, 0xFF404040, false);
        }
        pose.popPose();


        List<Component> tooltip = null;
        boolean hoverNode = mouseInGraph && wmx >= x && wmx < x + W && wmy >= y && wmy < y + H;
        if (hoverNode) tooltip = nodeTooltip(model, n, st);

        // machine chip
        if (s != null && !s.machines().isEmpty()) {
            // show the machine with a problem first, otherwise the first one
            var m = problem != null ? problem : s.machines().get(0);
            int cx0 = x + 6, cy0 = y + H + 1, cx1 = x + W - 6, cy1 = cy0 + TreeModel.CHIP_H;
            boolean jam = problem != null;
            boolean hoverChip = mouseInGraph && wmx >= cx0 && wmx < cx1 && wmy >= cy0 && wmy < cy1;
            g.fill(cx0, cy0, cx1, cy1, hoverChip ? 0xFF4F5AA0 : jam ? 0xFF7A2020 : 0xFF3A3F45);
            // A machine runs one recipe at a time. Only show its progress on the step it's actually working on
            // (decided by the machine's own current recipe / input; falls back to AE2's count if it can't tell).
            boolean forThisStep = m.workingOnThisStep(s.inMachines());
            if (forThisStep && m.state() == MachineState.WORKING && m.hasProgress()) {
                float frac = m.fraction();
                g.fill(cx0, cy1 - 1, cx0 + (int) ((cx1 - cx0) * frac), cy1, 0xFF66BB6A);
            }
            pose.pushPose();
            float hum = forThisStep && m.state() == MachineState.WORKING ? (float) Math.sin(now / 70.0) * 0.5f : 0;
            pose.translate(cx0 + 1, cy0 + 1 + hum, 0);
            pose.scale(0.625f, 0.625f, 1);
            g.renderItem(m.icon(), 0, 0);
            pose.popPose();

            String status = chipStatus(m);
            if (!forThisStep && m.state() == MachineState.WORKING) {
                // our items may be sitting in it, but it's processing something else right now
                status = s.inMachines() > 0 ? "§equeued" : "§7busy";
            }
            String more = s.machines().size() > 1 ? " §e+" + (s.machines().size() - 1) : "";
            pose.pushPose();
            pose.translate(cx0 + 13, cy0 + 3, 0);
            pose.scale(0.6f, 0.6f, 1);
            // use all the space up to the status text; cut with "…" only if it really doesn't fit
            int total = (int) ((cx1 - cx0 - 14) / 0.6f);
            int maxNameW = total - font.width(status) - font.width(more) - 6;
            String mname = m.name().getString();
            if (font.width(mname) > maxNameW) {
                mname = font.plainSubstrByWidth(mname, Math.max(10, maxNameW - font.width("…"))).stripTrailing() + "…";
            }
            g.drawString(font, mname + more, 0, 0, 0xFFE8E8E8, false);
            g.drawString(font, status, (int) ((cx1 - cx0 - 14) / 0.6f) - font.width(status) - 2, 0, 0xFFAAAAAA, false);
            pose.popPose();

            if (hoverChip) {
                tooltip = null;
                hoverPanel = new PanelRequest(m, s, forThisStep);
                registerChipClick(m);
            }
        }
        return tooltip;
    }

    private static MachineInfo pendingChip;

    private record PanelRequest(MachineInfo machine, SnapshotData.PatternState step, boolean forThisStep) {}

    @Nullable
    private static PanelRequest hoverPanel;

    private static final int SLOT = 18;

    /** Compact machine panel: name + position, slots in one row (in → out), real progress, one status line. */
    private static void drawMachinePanel(GuiGraphics g, Font font, PanelRequest req, int mx, int my, int sw, int sh) {
        var m = req.machine();
        var pose = g.pose();
        // ---- measure
        var groups = m.inventory();
        boolean running = m.state() == MachineState.WORKING || m.state() == MachineState.WAITING;
        // Prefer EMI's own recipe box for the recipe the machine is running; fall back to our compact row.
        var emiBox = running ? dev.ae2lattice.client.compat.EmiRecipeView.get(m.recipeId()) : null;
        var recipe = running && emiBox == null ? m.recipe() : List.<SnapshotData.InvGroup>of();
        int emiW = emiBox != null ? emiBox.getWidth(font) : 0, emiH = emiBox != null ? emiBox.getHeight() : 0;
        int rowW = Math.max(Math.max(rowWidth(groups), rowWidth(recipe) + 52), emiW);
        String pos = m.pos().getX() + " " + m.pos().getY() + " " + m.pos().getZ();
        int headW = font.width(m.name()) + 6 + (int) (font.width(pos) * 0.75f);
        String statusLine = null;
        int statusColor = 0xFFFF7070;
        var problem = m.state().problem ? m : null;
        if (problem != null) statusLine = "⚠ " + problemText(problem);
        else if (req.step().servedBy() != null) {
            statusLine = "outputs go to " + req.step().servedBy().getString() + " first";
            statusColor = 0xFFFFE66D;
        } else if (m.target() == SnapshotData.Target.OTHER && m.state() == MachineState.WORKING) {
            statusLine = "making " + (m.busyWith().isEmpty() ? "another recipe" : m.busyWith().getHoverName().getString());
            statusColor = 0xFFFFE66D;
        }
        boolean bar = req.forThisStep() && m.state() == MachineState.WORKING && m.hasProgress();
        int w = Math.max(headW, Math.max(rowW, statusLine != null ? font.width(statusLine) : 0)) + 12;
        int h = 6 + 10 + (emiBox != null ? emiH + 4 : 0) + (recipe.isEmpty() ? 0 : SLOT + 4) + (groups.isEmpty() ? 0 : 7 + SLOT + 2)
                + (bar ? 6 : 0) + (statusLine != null ? 11 : 0) + 4;
        int x = mx + 12, y = my + 10;
        if (x + w > sw - 4) x = mx - 12 - w;
        if (y + h > sh - 4) y = sh - 4 - h;

        pose.pushPose();
        pose.translate(0, 0, 300);
        // Minecraft's own tooltip background (notched corners, purple gradient border)
        net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil.renderTooltipBackground(
                g, x + 3, y + 3, w - 6, h - 6, 0);

        int cx = x + 6, cy = y + 5;
        g.drawString(font, m.name(), cx, cy, 0xFFFFFFFF, false);
        pose.pushPose();
        pose.translate(cx + font.width(m.name()) + 6, cy + 2, 0);
        pose.scale(0.75f, 0.75f, 1);
        g.drawString(font, pos, 0, 0, 0xFF888888, false);
        pose.popPose();
        cy += 11;

        if (emiBox != null) {
            // the recipe the machine is running, drawn by EMI exactly like its own hover box
            emiBox.renderImage(font, cx, cy, g);
            cy += emiH + 4;
        }
        if (!recipe.isEmpty()) {
            // the running recipe: GregTech already took these inputs out of the slots
            pose.pushPose();
            pose.translate(cx, cy + 6, 0);
            pose.scale(0.6f, 0.6f, 1);
            g.drawString(font, "processing", 0, 0, 0xFF66BB6A, false);
            pose.popPose();
            drawRow(g, font, recipe, cx + 52, cy, false);
            cy += SLOT + 4;
        }
        if (!groups.isEmpty()) {
            drawRow(g, font, groups, cx, cy, true);
            cy += 7 + SLOT + 2;
        }
        if (bar) {
            float frac = m.fraction();
            g.fill(cx, cy + 1, x + w - 6, cy + 3, 0xFF333333);
            g.fill(cx, cy + 1, cx + (int) ((x + w - 6 - cx) * frac), cy + 3, 0xFF66BB6A);
            cy += 6;
        }
        if (statusLine != null) g.drawString(font, statusLine, cx, cy + 1, statusColor, false);
        pose.popPose();
    }

    private static int rowWidth(List<SnapshotData.InvGroup> groups) {
        int w = 0;
        for (var gr : groups) w += groupWidth(gr) + 6;
        if (groups.stream().anyMatch(gr -> gr.kind().equals("out"))) w += 10;
        return w;
    }

    /** One row of groups: [in] [fuel] → [out]. Labels above the slots only for the real-slots row. */
    private static void drawRow(GuiGraphics g, Font font, List<SnapshotData.InvGroup> groups, int x, int y,
            boolean labels) {
        var pose = g.pose();
        int gx = x;
        int sy = labels ? y + 7 : y;
        boolean arrowDone = false;
        for (var gr : groups) {
            if (gr.kind().equals("out") && !arrowDone) {
                g.drawString(font, "→", gx, sy + 5, 0xFF888888, false);
                gx += 10;
                arrowDone = true;
            }
            if (labels) {
                pose.pushPose();
                pose.translate(gx, y, 0);
                pose.scale(0.6f, 0.6f, 1);
                g.drawString(font, gr.kind(), 0, 0, 0xFF888888, false);
                pose.popPose();
            }
            int sx = gx;
            for (var st : gr.items()) {
                boolean full = labels && gr.kind().equals("out") && st.getCount() >= st.getMaxStackSize();
                slot(g, sx, sy, full);
                g.renderItem(st, sx + 1, sy + 1);
                g.renderItemDecorations(font, st, sx + 1, sy + 1);
                sx += SLOT;
            }
            if (gr.emptySlots() > 0 && gr.items().size() < 9) {
                slot(g, sx, sy, false);
                sx += SLOT;
            }
            for (int i = 0; i < gr.fluids().size(); i++) {
                tank(g, font, sx, sy, gr.fluids().get(i), gr.fluidCapacity().get(i));
                sx += SLOT;
            }
            gx += groupWidth(gr) + 6;
        }
    }

    private static int groupWidth(SnapshotData.InvGroup gr) {
        int n = gr.items().size() + (gr.emptySlots() > 0 && gr.items().size() < 9 ? 1 : 0) + gr.fluids().size();
        return Math.max(1, n) * SLOT;
    }

    private static void slot(GuiGraphics g, int x, int y, boolean warn) {
        g.fill(x, y, x + SLOT, y + SLOT, warn ? 0xFFFF5555 : 0xFF373737);
        g.fill(x + 1, y + 1, x + SLOT, y + SLOT, warn ? 0xFFFF5555 : 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF8B8B8B);
    }

    private static void tank(GuiGraphics g, Font font, int x, int y, net.minecraftforge.fluids.FluidStack fluid, int cap) {
        g.fill(x, y, x + SLOT, y + SLOT, 0xFF555555);
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF222222);
        int color = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid.getFluid())
                .getTintColor(fluid) | 0xFF000000;
        float frac = cap > 0 ? Math.min(1f, fluid.getAmount() / (float) cap) : 1f;
        int fh = Math.max(1, (int) ((SLOT - 2) * frac));
        g.fill(x + 1, y + SLOT - 1 - fh, x + SLOT - 1, y + SLOT - 1, color);
        int mb = fluid.getAmount();
        String amt = mb >= 1000 ? (mb % 1000 == 0 ? (mb / 1000) + "B" : String.format("%.1fB", mb / 1000.0)) : mb + "";
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + SLOT - 1 - font.width(amt) * 0.5f, y + SLOT - 5, 200);
        pose.scale(0.5f, 0.5f, 1);
        g.drawString(font, amt, 0, 0, 0xFFFFFFFF, true);
        pose.popPose();
    }

    private static void registerChipClick(MachineInfo m) {
        pendingChip = m; // consumed by the click handler this frame
    }

    private static List<Component> nodeTooltip(TreeModel model, Node n, Status st) {
        var out = new ArrayList<Component>();
        out.add(n.key.getDisplayName().copy().withStyle(ChatFormatting.WHITE));
        var ks = model.keys.get(n.key);
        if (ks != null) {
            if (ks.stored() > 0) out.add(Component.literal(fmt(n.key, ks.stored()) + " ready").withStyle(ChatFormatting.GRAY));
        }
        long inMachines = model.inMachines(n);
        if (inMachines > 0) out.add(Component.literal(fmt(n.key, inMachines) + " in machine").withStyle(ChatFormatting.GREEN));
        else if (inMachines < 0) {
            out.add(Component.literal("In machine: unknown (recipe history unavailable)")
                    .withStyle(ChatFormatting.GOLD));
        }
        long pending = model.pendingOutput(n);
        if (pending > 0) out.add(Component.literal(fmt(n.key, pending) + " to do").withStyle(ChatFormatting.GOLD));
        var s = model.state(n);
        if (s != null) {
            if (s.servedBy() != null) {
                out.add(Component.literal("Waiting: AE2 gives these outputs to ").append(s.servedBy())
                        .append(" first").withStyle(ChatFormatting.YELLOW));
            }
            if (s.inMachines() > 0 && s.sinceOutputTicks() >= 20) {
                out.add(Component.literal("Last output: " + fmtDuration(s.sinceOutputTicks() * 50L) + " ago").withStyle(ChatFormatting.GRAY));
            }
            for (var m : s.machines()) {
                if (model.hasProblem(s) && m.state().problem) {
                    out.add(Component.literal("⚠ ").append(m.name()).append(": " + problemText(m)).withStyle(ChatFormatting.RED));
                }
            }
        } else if (n.isLeaf() && inMachines > 0) {
            out.add(Component.literal("CPU item total; no recorded recipe").withStyle(ChatFormatting.DARK_GRAY));
        } else if (n.isLeaf() && n != model.root) {
            out.add(Component.literal("Raw material, pulled from ME storage").withStyle(ChatFormatting.DARK_GRAY));
        }
        return out;
    }

    private static List<Component> chipTooltip(List<MachineInfo> ms) {
        var out = new ArrayList<Component>();
        for (int i = 0; i < Math.min(5, ms.size()); i++) {
            var m = ms.get(i);
            out.add(m.name().copy().withStyle(i == 0 ? ChatFormatting.WHITE : ChatFormatting.GRAY));
            out.add(Component.literal("  " + chipStatus(m)));
            if (m.target() == SnapshotData.Target.OTHER && m.state() == MachineState.WORKING) {
                var what = m.busyWith().isEmpty() ? Component.literal("other recipe") : m.busyWith().getHoverName();
                out.add(Component.literal("  making ").append(what).withStyle(ChatFormatting.YELLOW));
            }
            if (m.reason() != null) out.add(Component.literal("  ").append(m.reason()).withStyle(ChatFormatting.RED));
            out.add(Component.literal("  " + m.pos().getX() + ", " + m.pos().getY() + ", " + m.pos().getZ()
                    + "  (" + m.dimension().getPath() + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (ms.size() > 5) out.add(Component.literal("…and " + (ms.size() - 5) + " more").withStyle(ChatFormatting.DARK_GRAY));
        out.add(Component.literal("Click to highlight").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    /** Short status text, straight from what the machine reports. */
    private static String chipStatus(MachineInfo m) {
        return switch (m.state()) {
            case WORKING -> m.isPercent() ? "§a" + m.progress() + "%" : m.maxProgress() > 0
                    ? "§a" + secs(m.progress()) + " / " + secs(m.maxProgress()) + "s"
                    : "§aworking";
            case IDLE -> "§7idle";
            case WAITING -> "§c" + problemText(m);
            case SUSPENDED -> "§cswitched off";
            case NO_FUEL -> "§cno fuel";
            case OUTPUT_FULL -> "§coutput full";
            case INPUT_FULL -> "§einput full";
            case UNLOADED -> "§7not loaded";
            case UNKNOWN -> "§7(no status)";
        };
    }

    private static String problemText(MachineInfo m) {
        return switch (m.state()) {
            case WAITING -> m.reason() != null ? m.reason().getString() : "waiting";
            case SUSPENDED -> "switched off";
            case NO_FUEL -> "no fuel";
            case OUTPUT_FULL -> "output full";
            default -> m.state().name().toLowerCase();
        };
    }

    private static String secs(int ticks) {
        return String.format("%.1f", ticks / 20.0);
    }

    private static void drawFlyers(GuiGraphics g, long now) {
        var mc = Minecraft.getInstance();
        var pose = g.pose();
        var it = flyers.iterator();
        while (it.hasNext()) {
            var f = it.next();
            float t = (now - f.start) / (float) f.dur;
            if (t < 0) continue;
            if (t >= 1) {
                it.remove();
                continue;
            }
            float px, py, scale;
            if (f.pop) {
                px = f.node.x + TreeModel.NODE_W - 8 + t * 14;
                py = f.node.y + 4 - t * 14;
                scale = 0.55f + 0.25f * (float) Math.sin(t * Math.PI);
            } else {
                // straight along the drawn line, no hop
                float te = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
                float[] pt = along(edgePath(f.edge), te);
                px = pt[0];
                py = pt[1];
                scale = 0.7f;
            }
            pose.pushPose();
            pose.translate(px, py, 60);
            pose.scale(scale, scale, 1);
            AEKeyRendering.drawInGui(mc, g, -8, -8, f.key);
            pose.popPose();
        }
    }

    private static float[] along(int[][] p, float t) {
        float total = 0;
        float[] seg = new float[p.length - 1];
        for (int i = 0; i < seg.length; i++) {
            seg[i] = Math.abs(p[i + 1][0] - p[i][0]) + Math.abs(p[i + 1][1] - p[i][1]);
            total += seg[i];
        }
        float d = t * total;
        for (int i = 0; i < seg.length; i++) {
            if (d <= seg[i] || i == seg.length - 1) {
                float k = seg[i] == 0 ? 0 : Math.min(1, d / seg[i]);
                return new float[] { p[i][0] + (p[i + 1][0] - p[i][0]) * k, p[i][1] + (p[i + 1][1] - p[i][1]) * k };
            }
            d -= seg[i];
        }
        return new float[] { p[p.length - 1][0], p[p.length - 1][1] };
    }

    /** Same info as AE2's own CPU tooltip: name, storage, co-processors, current job, progress + time. */
    private static List<com.mojang.datafixers.util.Either<net.minecraft.network.chat.FormattedText,
            net.minecraft.world.inventory.tooltip.TooltipComponent>> cpuTooltipRich(
            CraftingStatusMenu.CraftingCpuListEntry cpu, String name) {
        var out = new ArrayList<com.mojang.datafixers.util.Either<net.minecraft.network.chat.FormattedText,
                net.minecraft.world.inventory.tooltip.TooltipComponent>>();
        var lines = cpuTooltip(cpu, name);
        var job = cpu.currentJob();
        for (int i = 0; i < lines.size(); i++) {
            // the "Crafting N <item>" line gets the item's icon in front of it
            if (job != null && i == lines.size() - 2) out.add(com.mojang.datafixers.util.Either.right(new IconLine(job.what(), lines.get(i))));
            else out.add(com.mojang.datafixers.util.Either.left(lines.get(i)));
        }
        return out;
    }

    private static List<Component> cpuTooltip(CraftingStatusMenu.CraftingCpuListEntry cpu, String name) {
        final int num = 0x8C8CFF, bad = 0xFF4545;
        var out = new ArrayList<Component>();
        out.add(Component.literal(name));
        long kb = cpu.storage() / 1024;
        String storage = cpu.storage() % 1024 == 0 && kb > 0
                ? (kb >= 1024 && kb % 1024 == 0 ? (kb / 1024) + "M" : kb + "k") : String.valueOf(cpu.storage());
        out.add(Component.literal(storage).withStyle(s -> s.withColor(num))
                .append(Component.literal(" Storage").withStyle(ChatFormatting.GRAY)));
        if (cpu.coProcessors() > 0) {
            out.add(Component.literal(String.valueOf(cpu.coProcessors())).withStyle(s -> s.withColor(num))
                    .append(Component.literal(" Co-Processors").withStyle(ChatFormatting.GRAY)));
        }
        var job = cpu.currentJob();
        if (job != null) {
            out.add(Component.literal("Crafting ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(fmt(job.what(), job.amount())).withStyle(s -> s.withColor(num)))
                    .append(Component.literal(" ").append(job.what().getDisplayName()).withStyle(ChatFormatting.GRAY)));
            out.add(Component.literal("Crafted ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.format("%.2f%%", cpu.progress() * 100)).withStyle(s -> s.withColor(bad)))
                    .append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(shortDuration(TimeUnit.NANOSECONDS.toMillis(cpu.elapsedTimeNanos())))
                            .withStyle(s -> s.withColor(num))));
        }
        return out;
    }

    private static String shortDuration(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        if (h > 0) return h + "h" + m + "m";
        if (m > 0) return m + "m" + (s % 60) + "s";
        return (s % 60) + "s";
    }

    // ---- AE2's own CPU list, drawn exactly like appeng.client.gui.widgets.CPUSelectionList
    private static final net.minecraft.resources.ResourceLocation AE_STATES =
            new net.minecraft.resources.ResourceLocation("ae2", "textures/guis/states.png");
    private static final net.minecraft.resources.ResourceLocation CREATIVE_TABS =
            new net.minecraft.resources.ResourceLocation("textures/gui/container/creative_inventory/tabs.png");
    private static final int CPU_ROWS = 6, AE_TEXT = 0xFF404040, AE_SELECTED = 0x00B4D8;
    private static int cpuScroll;
    private static int cpuListX, cpuListY, cpuListW, cpuListH;

    /** AE2-style CPU list (no extra window frame), filling the side column from top to bottom. */
    private static void drawAeCpuList(GuiGraphics g, Font font, CraftingStatusMenu sm, int px, int py, int bottom,
            int mx, int my) {
        var mc = Minecraft.getInstance();
        var pose = g.pose();
        g.drawString(font, Component.translatable("gui.ae2.CraftingCPUs"), px + 1, py + 1, AE_TEXT, false);

        // sunken list box: rows (67 wide buttons) + scrollbar track, same look as AE2's texture
        int bx0 = px, by0 = py + 12, rowsW = 69, trackW = 14, bx1 = bx0 + 2 + rowsW + trackW, by1 = bottom;
        cpuListX = bx0; cpuListY = by0; cpuListW = bx1 - bx0; cpuListH = by1 - by0;
        g.fill(bx0, by0, bx1, by1, 0xFF373737);                       // dark top/left edge
        g.fill(bx0 + 1, by0 + 1, bx1, by1, 0xFFFFFFFF);               // light bottom/right edge
        int rx0 = bx0 + 1, ry0 = by0 + 1, rx1 = rx0 + rowsW, ry1 = by1 - 1;
        g.fill(rx0, ry0, rx1, ry1, 0xFFDCDCDC);                       // rows background
        int rows = Math.max(1, (ry1 - ry0) / 23);
        for (int r = 1; r <= rows; r++) {
            int sy = ry0 + r * 23 - 1;
            if (sy < ry1) g.fill(rx0, sy, rx1, sy + 1, 0xFF8B8B8B);  // separator lines like AE2
        }
        int tx0 = rx1 + 1, tx1 = bx1 - 1;
        g.fill(tx0, ry0, tx1, ry1, 0xFF8B8B8B);                       // scrollbar track

        var all = sm.cpuList.cpus();
        int hidden = Math.max(0, all.size() - rows);
        cpuScroll = Math.max(0, Math.min(cpuScroll, hidden));
        int trackH = ry1 - ry0;
        int handleY = ry0 + (hidden == 0 ? 0 : (int) ((trackH - 15) * (cpuScroll / (float) hidden)));
        g.blit(CREATIVE_TABS, tx0, handleY, hidden == 0 ? 244 : 232, 0, 12, 15, 256, 256);

        int x = rx0 + 1, y = ry0;
        int selected = sm.getSelectedCpuSerial();
        for (int i = cpuScroll; i < Math.min(all.size(), cpuScroll + rows); i++) {
            var cpu = all.get(i);
            boolean sel = cpu.serial() == selected;
            RenderSystem.setShaderColor(sel ? 0 : 1, sel ? 0xB4 / 255f : 1, sel ? 0xD8 / 255f : 1, 1);
            g.blit(AE_CPU_SELECTOR, x, y, 100, 0, 67, 22, 256, 256);
            RenderSystem.setShaderColor(1, 1, 1, 1);

            String name = cpu.name() != null ? cpu.name().getString() : "CPU #" + cpu.serial();
            pose.pushPose();
            pose.translate(x + 3, y + 3, 0);
            pose.scale(0.8f, 0.8f, 1);
            g.drawString(font, name, 0, 0, AE_TEXT, false);
            pose.popPose();

            // info bar at 60% scale, like AE2
            pose.pushPose();
            pose.translate(x + 2, y + 22 - 12, 0);
            pose.scale(0.6f, 0.6f, 1);
            int ix = 0;
            var job = cpu.currentJob();
            if (job != null) {
                g.blit(AE_STATES, ix, 0, 48, 144, 16, 16, 256, 256);          // craft hammer
                ix += 16 + 2;
                String amt = job.what().formatAmount(job.amount(), AmountFormat.SLOT);
                g.drawString(font, amt, ix, 4, AE_TEXT, false);
                ix += font.width(amt) + 1;
                AEKeyRendering.drawInGui(mc, g, ix, 0, job.what());
            } else {
                g.blit(AE_STATES, ix, 0, 64, 64, 16, 16, 256, 256);           // storage level icon
                ix += 16 + 1;
                String st = formatStorage(cpu.storage());
                g.drawString(font, st, ix, 4, AE_TEXT, false);
                ix += font.width(st) + 1;
                if (cpu.coProcessors() > 0) {
                    g.blit(AE_STATES, ix, 0, 64, 16, 16, 16, 256, 256);       // co-processor icon
                    ix += 16;
                    g.drawString(font, String.valueOf(cpu.coProcessors()), ix, 4, AE_TEXT, false);
                }
            }
            pose.popPose();
            if (job != null) {
                int progress = (int) (cpu.progress() * (67 - 1));
                g.fill(x + 1, y + 22 - 2, x + progress, y + 22 - 1, sel ? 0xFFFFFFFF : 0xFF000000 | AE_SELECTED);
            }

            int serial = cpu.serial();
            hits.add(new Hit(x, y, x + 67, y + 22, () -> {
                if (serial == sm.getSelectedCpuSerial()) return; // already showing this CPU
                sm.selectCpu(serial);
                ClientState.MODEL.clear();
                ClientState.resubscribe();
                needFit = true;
                flyers.clear();
            }, null, cpuTooltipRich(cpu, name)));
            y += 22 + 1;
        }
    }

    private static String formatStorage(long bytes) {
        if (bytes >= 1_000_000) return String.format("%.2fM", bytes / 1_000_000.0);
        if (bytes >= 1_000) return String.format("%.2fK", bytes / 1_000.0);
        return String.valueOf(bytes);
    }

    private static boolean overCpuList(double mx, double my) {
        return mx >= cpuListX && mx < cpuListX + cpuListW && my >= cpuListY && my < cpuListY + cpuListH;
    }

    private static void drawCpuList(GuiGraphics g, Font font, CraftingStatusMenu sm, int x0, int y0, int x1, int y1,
            int mx, int my) {
        bevel(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, C_INNER, C_DARK, C_LIGHT);
        var mc = Minecraft.getInstance();
        int y = y0 + 2;
        int selected = sm.getSelectedCpuSerial();
        for (var cpu : sm.cpuList.cpus()) {
            if (y + 22 > y1) break;
            boolean sel = cpu.serial() == selected;
            boolean hov = mx >= x0 && mx < x1 && my >= y && my < y + 22;
            // AE2's own CPU list button (cpu_selector.png), tinted like AE2 when selected
            aeCpuButton(g, x0 + 2, y, x1 - x0 - 4, 22, sel ? 0x00B4D8 : hov ? 0xEEEEEE : 0xFFFFFF);
            String name = cpu.name() != null ? cpu.name().getString() : "CPU #" + cpu.serial();
            g.drawString(font, font.plainSubstrByWidth(name, x1 - x0 - 12), x0 + 6, y + 3, 0xFF404040, false);
            var job = cpu.currentJob();
            if (job != null) {
                // AE2-style info row: amount + item, and a progress line along the bottom
                var pose = g.pose();
                String amt = fmt(job.what(), job.amount());
                g.drawString(font, amt, x0 + 6, y + 12, 0xFF404040, false);
                pose.pushPose();
                pose.translate(x0 + 8 + font.width(amt), y + 11, 0);
                pose.scale(0.6f, 0.6f, 1);
                AEKeyRendering.drawInGui(mc, g, 0, 0, job.what());
                pose.popPose();
                int bx0 = x0 + 3, bx1 = x1 - 3;
                g.fill(bx0, y + 20, bx0 + (int) ((bx1 - bx0) * cpu.progress()), y + 21, sel ? 0xFFFFFFFF : 0xFF00B4D8);
            }
            int serial = cpu.serial();
            hits.add(new Hit(x0 + 2, y, x1 - 2, y + 22, () -> {
                if (serial == sm.getSelectedCpuSerial()) return; // already showing this CPU
                sm.selectCpu(serial);
                ClientState.MODEL.clear();
                ClientState.resubscribe(); // ask the server for the full tree of the new CPU
                needFit = true;
                flyers.clear();
            }, null, cpuTooltipRich(cpu, name)));
            y += 24;
        }
    }

    // ============================================================================================
    // input

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre e) {
        if (!active(e.getScreen())) return;
        e.setCanceled(true);
        double mx = e.getMouseX(), my = e.getMouseY();
        if (e.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (var hit : hits) {
                if (hit.contains(mx, my) && hit.click != null) {
                    playClick();
                    hit.click.run();
                    return;
                }
            }
            if (pendingChip != null && inGraph(mx, my) && overChip(mx, my)) {
                highlight(pendingChip, Screen.hasShiftDown());
                return;
            }
        }
        if (inGraph(mx, my)) {
            dragging = true;
            dragMoved = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre e) {
        if (!active(e.getScreen())) return;
        e.setCanceled(true);
        dragging = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre e) {
        if (!active(e.getScreen())) return;
        e.setCanceled(true);
        if (dragging) {
            camX -= (float) (e.getDragX() / zoom);
            camY -= (float) (e.getDragY() / zoom);
            dragMoved = true;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre e) {
        if (!active(e.getScreen())) return;
        e.setCanceled(true);
        double mx = e.getMouseX(), my = e.getMouseY();
        if (overCpuList(mx, my)) {
            cpuScroll += e.getScrollDelta() > 0 ? -1 : 1;
            return;
        }
        if (!inGraph(mx, my)) return;
        float nz = Math.max(0.3f, Math.min(3f, zoom * (e.getScrollDelta() > 0 ? 1.12f : 1 / 1.12f)));
        // zoom around the cursor
        float wx = (float) ((mx - gx0) / zoom + camX), wy = (float) ((my - gy0) / zoom + camY);
        zoom = nz;
        camX = (float) (wx - (mx - gx0) / zoom);
        camY = (float) (wy - (my - gy0) / zoom);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre e) {
        if (!active(e.getScreen())) return;
        int key = e.getKeyCode();
        var mc = Minecraft.getInstance();
        if (key == GLFW.GLFW_KEY_ESCAPE || mc.options.keyInventory.matches(key, e.getScanCode())) return; // let it close
        e.setCanceled(true);
        if (key == GLFW.GLFW_KEY_F) needFit = true;
        if (key == GLFW.GLFW_KEY_G) setTreeMode(false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre e) {
        if (active(e.getScreen())) e.setCanceled(true);
    }

    private static boolean inGraph(double x, double y) {
        return x >= gx0 && x < gx1 && y >= gy0 && y < gy1;
    }

    private static boolean overChip(double mx, double my) {
        double wx = (mx - gx0) / zoom + camX, wy = (my - gy0) / zoom + camY;
        for (var n : ClientState.MODEL.nodes) {
            var s = ClientState.MODEL.state(n);
            if (s == null || s.machines().isEmpty()) continue;
            int cx0 = Math.round(n.x) + 6, cy0 = Math.round(n.y) + TreeModel.NODE_H + 1;
            if (wx >= cx0 && wx < cx0 + TreeModel.NODE_W - 12 && wy >= cy0 && wy < cy0 + TreeModel.CHIP_H) {
                pendingChip = s.machines().get(0);
                return true;
            }
        }
        return false;
    }

    private static void highlight(MachineInfo m, boolean keepOpen) {
        highlightAt(m.pos(), m.dimension(), m.name(), keepOpen);
    }

    private static void highlightAt(net.minecraft.core.BlockPos pos, net.minecraft.resources.ResourceLocation dim,
            Component name, boolean keepOpen) {
        highlightImpl(new MachineInfo(pos, dim, ItemStack.EMPTY, name, MachineState.UNKNOWN, 0, 0, null,
                SnapshotData.Target.UNKNOWN, ItemStack.EMPTY, List.of(), List.of(), null), keepOpen);
    }

    private static void highlightImpl(MachineInfo m, boolean keepOpen) {
        var mc = Minecraft.getInstance();
        playClick();
        Highlighter.highlight(m.pos(), m.dimension(), LatticeConfig.HIGHLIGHT_SECONDS.get());
        if (mc.player != null) {
            var here = mc.level != null && mc.level.dimension().location().equals(m.dimension());
            var msg = Component.literal("[Lattice] ").withStyle(ChatFormatting.AQUA)
                    .append(m.name().copy().withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" at " + m.pos().getX() + ", " + m.pos().getY() + ", " + m.pos().getZ())
                            .withStyle(ChatFormatting.GRAY));
            if (here) {
                double dist = Math.sqrt(mc.player.blockPosition().distSqr(m.pos()));
                msg.append(Component.literal(" (" + (int) dist + " blocks)").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                msg.append(Component.literal(" in " + m.dimension()).withStyle(ChatFormatting.YELLOW));
            }
            mc.player.displayClientMessage(msg, false);
            if (!keepOpen && LatticeConfig.CLOSE_ON_HIGHLIGHT.get()) mc.player.closeContainer();
        }
    }

    // ============================================================================================
    // helpers

    private static void fit(TreeModel m) {
        float bw = m.maxX - m.minX + 40, bh = m.maxY - m.minY + 40;
        float vw = gx1 - gx0, vh = gy1 - gy0;
        if (vw <= 0 || vh <= 0) return;
        zoom = Math.max(0.3f, Math.min(1.5f, Math.min(vw / bw, vh / bh)));
        camX = m.minX - 20 - (vw / zoom - bw) / 2;
        camY = m.minY - 20 - (vh / zoom - bh) / 2;
        needFit = false;
    }

    private static int headerButton(GuiGraphics g, Font font, int right, String label, int mx, int my,
            Runnable click, List<Component> tooltip) {
        int w = font.width(label) + 12;
        int x0 = right - w, y0 = py0 + 3, x1 = right, y1 = py0 + 18;
        boolean hov = mx >= x0 && mx < x1 && my >= y0 && my < y1;
        mcButton(g, x0, y0, w, y1 - y0, hov);
        g.drawString(font, label, x0 + (w - font.width(label)) / 2, y0 + (y1 - y0 - 8) / 2, hov ? 0xFFFFFFA0 : 0xFFE0E0E0, true);
        hits.add(new Hit(x0, y0, x1, y1, click, tooltip));
        return x0 - 3;
    }

    /** Minecraft's own button texture (gui/widgets.png), 9-sliced like vanilla buttons. */
    private static final net.minecraft.resources.ResourceLocation WIDGETS =
            new net.minecraft.resources.ResourceLocation("textures/gui/widgets.png");

    private static final net.minecraft.resources.ResourceLocation AE_CPU_SELECTOR =
            new net.minecraft.resources.ResourceLocation("ae2", "textures/guis/cpu_selector.png");

    /** AE2's crafting CPU list button (cpu_selector.png 100,0 67x22), stretched and tinted like AE2 does. */
    private static void aeCpuButton(GuiGraphics g, int x, int y, int w, int h, int rgb) {
        RenderSystem.setShaderColor(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f);
        g.blitNineSliced(AE_CPU_SELECTOR, x, y, w, h, 3, 3, 67, 22, 100, 0);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static void mcButton(GuiGraphics g, int x, int y, int w, int h, boolean highlighted) {
        g.blitNineSliced(WIDGETS, x, y, w, h, 20, 4, 200, 20, 0, highlighted ? 86 : 66);
    }

    private static int legend(GuiGraphics g, Font font, int x, int y, int color, String label) {
        g.fill(x, y, x + 10, y + 8, 0xFF555555);
        g.fill(x + 1, y + 1, x + 9, y + 7, color);
        g.drawString(font, label, x + 13, y, 0xFF404040, false);
        return x + 13 + font.width(label) + 10;
    }

    private static void drawDots(GuiGraphics g) {
        int step = Math.max(6, Math.round(20 * zoom));
        int ox = Math.floorMod(Math.round(-camX * zoom), step), oy = Math.floorMod(Math.round(-camY * zoom), step);
        for (int x = gx0 + ox; x < gx1; x += step)
            for (int y = gy0 + oy; y < gy1; y += step) g.fill(x, y, x + 1, y + 1, 0x18000000);
    }

    private static void centered(GuiGraphics g, Font font, String s, int color) {
        g.drawString(font, s, (gx0 + gx1 - font.width(s)) / 2, (gy0 + gy1) / 2 - 8, color, false);
    }

    private static void centered2(GuiGraphics g, Font font, String s) {
        g.drawString(font, s, (gx0 + gx1 - font.width(s)) / 2, (gy0 + gy1) / 2 + 4, 0xFF555555, false);
    }

    private static void bevel(GuiGraphics g, int x0, int y0, int x1, int y1, int fill, int tl, int br) {
        g.fill(x0, y0, x1, y1, br);
        g.fill(x0, y0, x1 - 1, y1 - 1, tl);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill);
    }

    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int col) {
        if (y1 == y2) g.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + 1, col);
        else g.fill(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, col);
    }

    private static void dashed(GuiGraphics g, int x1, int y1, int x2, int y2, int off, int col) {
        boolean horiz = y1 == y2;
        int a = horiz ? x1 : y1, b = horiz ? x2 : y2;
        int dir = b >= a ? 1 : -1;
        int len = Math.abs(b - a);
        for (int d = -off; d < len; d += 8) {
            int s0 = Math.max(0, d), s1 = Math.min(len, d + 5);
            if (s1 <= s0) continue;
            int p0 = a + dir * s0, p1 = a + dir * s1;
            if (horiz) g.fill(Math.min(p0, p1), y1 - 1, Math.max(p0, p1), y1 + 2, col);
            else g.fill(x1 - 1, Math.min(p0, p1), x1 + 2, Math.max(p0, p1), col);
        }
    }

    private static String fmt(AEKey key, long amount) {
        return key.formatAmount(amount, AmountFormat.SLOT);
    }

    private static String fmtDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return String.format("%d:%02d", s / 60, s % 60);
        return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String fmtDurationPrecise(long ms) {
        if (ms < 10_000) return String.format("%.1fs", ms / 1000.0);
        return fmtDuration(ms);
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private TreeOverlay() {}
}
