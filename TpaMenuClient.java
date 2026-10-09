package pl.tpamenu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class TpaMenuClient implements ClientModInitializer {

    private static final Set<String> ACCEPT_COMMANDS =
            Set.of("tpaccept", "etpaccept", "tpyes", "tpayes", "etpyes");

    private static final List<String> KEYWORDS = List.of(
            "wants to teleport to you", "requested to teleport to you",
            "request to teleport to you", "teleport request from",
            "sent you a teleport request", "sent you a tpa",
            "teleported to you", "teleporting to you",
            "chce się do ciebie teleportować", "chce sie do ciebie teleportowac",
            "prośbę o teleportację", "prosbe o teleportacje",
            "teleportował się do ciebie", "teleportowal sie do ciebie"
    );

    private static final List<String> EXCLUDE = List.of(
            "you sent", "request sent", "wysłano", "wyslano", "wysłałeś", "wyslales",
            "expired", "wygasł", "wygasl", "denied", "odrzuc"
    );

    private static final long[] TTLS = {60_000L, 300_000L, 900_000L};
    private static final long FRESH_MS = 10_000;
    private static final long ARM_MS = 15_000;
    private static final double NEAR = 6.0;
    private static final double FAR = 12.0;
    private static final int[] DELAYS = {20, 40, 60};

    static class Entry {
        final String nick;
        long time;
        boolean checked;
        boolean waiting;
        final List<String> lines = new ArrayList<>();
        Entry(String nick, long time) { this.nick = nick; this.time = time; }
    }

    static final LinkedHashMap<String, Entry> REQUESTS = new LinkedHashMap<>();
    static final ArrayDeque<String> SEND_QUEUE = new ArrayDeque<>();
    static boolean autoCheck = false;
    static boolean notifyOn = true;
    static boolean closeAfter = false;
    static boolean captureOn = true;
    static int delayIdx = 0;
    static int ttlIdx = 1;
    static KeyBinding openKey;

    static Entry capturing = null;
    static long captureStart = 0;
    static long captureEnd = 0;

    private static int sendCooldown = 0;
    private long armedUntil = 0;
    private final Map<UUID, Double> lastDist = new HashMap<>();

    @Override
    public void onInitializeClient() {
        loadConfig();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tpamenu.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.tpamenu"));

        ClientSendMessageEvents.COMMAND.register(command -> {
            String[] parts = command.trim().split("\\s+");
            if (parts.length >= 1 && ACCEPT_COMMANDS.contains(parts[0].toLowerCase())) {
                if (parts.length >= 2) {
                    addRequest(parts[1]);
                } else {
                    armedUntil = System.currentTimeMillis() + ARM_MS;
                }
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = message.getString();

            // Przechwytywanie odpowiedzi na /stats
            if (capturing != null) {
                long now = System.currentTimeMillis();
                if (now > captureEnd) {
                    capturing.waiting = false;
                    capturing = null;
                } else {
                    String s = text.trim();
                    if (!s.isEmpty() && capturing.lines.size() < 40) {
                        capturing.lines.add(s);
                        captureEnd = Math.min(captureStart + 5000, now + 1200);
                    }
                }
            }

            String lower = text.toLowerCase();
            for (String ex : EXCLUDE) {
                if (lower.contains(ex)) return;
            }
            boolean match = false;
            for (String k : KEYWORDS) {
                if (lower.contains(k)) { match = true; break; }
            }
            if (!match) return;
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                String nick = findNick(client, text);
                if (nick != null) addRequest(nick);
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null && client.player != null) {
                    client.setScreen(new TpaScreen());
                }
            }

            if (capturing != null && System.currentTimeMillis() > captureEnd) {
                capturing.waiting = false;
                capturing = null;
            }

            if (sendCooldown > 0) {
                sendCooldown--;
            } else if (!SEND_QUEUE.isEmpty() && client.player != null && client.player.networkHandler != null) {
                String nick = SEND_QUEUE.poll();
                Entry e = REQUESTS.get(nick);
                if (e != null) {
                    sendStats(client, e);
                } else {
                    client.player.networkHandler.sendChatCommand("stats " + nick);
                }
                sendCooldown = DELAYS[delayIdx];
            }

            if (client.player == null || client.world == null) {
                lastDist.clear();
                return;
            }

            boolean armed = System.currentTimeMillis() < armedUntil;
            Map<UUID, Double> now = new HashMap<>();
            for (AbstractClientPlayerEntity other : client.world.getPlayers()) {
                if (other == client.player) continue;
                double dist = other.distanceTo(client.player);
                now.put(other.getUuid(), dist);
                Double prev = lastDist.get(other.getUuid());
                if (armed && dist < NEAR && (prev == null || prev > FAR)) {
                    addRequest(other.getName().getString());
                    armedUntil = 0;
                    armed = false;
                }
            }
            lastDist.clear();
            lastDist.putAll(now);
        });
    }

    // ---------------- logika ----------------

    static void sendStats(MinecraftClient mc, Entry e) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        if (captureOn) {
            long now = System.currentTimeMillis();
            e.lines.clear();
            e.waiting = true;
            capturing = e;
            captureStart = now;
            captureEnd = now + 2500;
        }
        mc.player.networkHandler.sendChatCommand("stats " + e.nick);
        e.checked = true;
    }

    static void addRequest(String nick) {
        long now = System.currentTimeMillis();
        Entry old = REQUESTS.get(nick);
        boolean fresh = old == null || now - old.time > FRESH_MS;
        Entry e = new Entry(nick, now);
        if (old != null) {
            e.lines.addAll(old.lines);
            if (!fresh) e.checked = old.checked;
        }
        REQUESTS.remove(nick);
        REQUESTS.put(nick, e);

        if (fresh) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (notifyOn && mc.player != null) {
                mc.player.sendMessage(Text.literal("[TPA] " + nick + " teleportuje się do Ciebie ("
                        + openKey.getBoundKeyLocalizedText().getString() + " = menu)"), true);
            }
            if (autoCheck) enqueue(nick);
        }
    }

    static void enqueue(String nick) {
        if (!SEND_QUEUE.contains(nick)) SEND_QUEUE.add(nick);
    }

    static List<Entry> activeEntries() {
        long now = System.currentTimeMillis();
        long ttl = TTLS[ttlIdx];
        Iterator<Map.Entry<String, Entry>> it = REQUESTS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().time > ttl) it.remove();
        }
        List<Entry> list = new ArrayList<>(REQUESTS.values());
        Collections.reverse(list);
        return list;
    }

    private static String findNick(MinecraftClient client, String text) {
        if (client.getNetworkHandler() == null) return null;
        String self = client.getSession().getUsername();
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            String name = entry.getProfile().getName();
            if (name == null || name.equalsIgnoreCase(self)) continue;
            Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            if (p.matcher(text).find()) return name;
        }
        return null;
    }

    // ---------------- konfiguracja ----------------

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("tpamenu.properties");
    }

    static void loadConfig() {
        try {
            Path p = configPath();
            if (Files.exists(p)) {
                Properties pr = new Properties();
                try (InputStream in = Files.newInputStream(p)) { pr.load(in); }
                autoCheck = Boolean.parseBoolean(pr.getProperty("autoCheck", "false"));
                notifyOn = Boolean.parseBoolean(pr.getProperty("notify", "true"));
                closeAfter = Boolean.parseBoolean(pr.getProperty("closeAfter", "false"));
                captureOn = Boolean.parseBoolean(pr.getProperty("capture", "true"));
                delayIdx = Math.max(0, Math.min(DELAYS.length - 1, Integer.parseInt(pr.getProperty("delay", "0"))));
                ttlIdx = Math.max(0, Math.min(TTLS.length - 1, Integer.parseInt(pr.getProperty("ttl", "1"))));
            }
        } catch (Exception ignored) { }
    }

    static void saveConfig() {
        try {
            Properties pr = new Properties();
            pr.setProperty("autoCheck", String.valueOf(autoCheck));
            pr.setProperty("notify", String.valueOf(notifyOn));
            pr.setProperty("closeAfter", String.valueOf(closeAfter));
            pr.setProperty("capture", String.valueOf(captureOn));
            pr.setProperty("delay", String.valueOf(delayIdx));
            pr.setProperty("ttl", String.valueOf(ttlIdx));
            try (OutputStream out = Files.newOutputStream(configPath())) { pr.store(out, "TPA Menu"); }
        } catch (Exception ignored) { }
    }

    // ---------------- GUI ----------------

    public static class TpaScreen extends Screen {

        private static final int PANEL_BG = 0xFF121418;
        private static final int PANEL_BORDER = 0xFF2A2E36;
        private static final int CARD = 0xFF1A1D23;
        private static final int CARD_BORDER = 0xFF262A32;
        private static final int ROW = 0xFF191C22;
        private static final int ROW_HOVER = 0xFF232731;
        private static final int INPUT = 0xFF0E1013;
        private static final int BTN = 0xFF252932;
        private static final int BTN_HOVER = 0xFF313744;
        private static final int ACCENT = 0xFF5B8CFF;
        private static final int ACCENT_HOVER = 0xFF7AA2FF;
        private static final int ACCENT_SOFT = 0xFF212C4A;
        private static final int ACCENT_TEXT = 0xFF9DB8FF;
        private static final int GREEN = 0xFF3DD68C;
        private static final int GREEN_SOFT = 0xFF173528;
        private static final int AMBER = 0xFFF5A524;
        private static final int RED = 0xFFFF5C5C;
        private static final int RED_SOFT = 0xFF4A2226;
        private static final int TEXT = 0xFFF2F4F8;
        private static final int MUTED = 0xFF8B93A1;

        private static final int ROW_H = 40;
        private static final int ROW_PITCH = 44;
        private static final int MAX_ROWS = 6;
        private static final String[] CHIPS = {"Wszyscy", "Nowi", "Sprawdzeni"};
        private static final String[] SET_LABELS = {
                "Auto-sprawdzanie", "Powiadomienia", "Zamykaj po kliknięciu", "Przechwytuj odpowiedź"};
        private static final String[] SET_DESCS = {
                "Wysyłaj /stats automatycznie nowym graczom",
                "Komunikat nad paskiem szybkiego dostępu",
                "Zamknij okno po wysłaniu /stats",
                "Pokazuj wynik /stats w szczegółach gracza"};

        private static final int V_LIST = 0;
        private static final int V_DETAIL = 1;
        private static final int V_SETTINGS = 2;

        private final long openStart = System.currentTimeMillis();
        private long lastNano = System.nanoTime();
        private float fade = 0f;
        private int slide = 0;

        private int view = V_LIST;
        private Entry detail = null;
        private String selNick = null;

        private float scrollPos = 0f;
        private float scrollTarget = 0f;
        private final float[] toggleAnim = {
                autoCheck ? 1f : 0f, notifyOn ? 1f : 0f, closeAfter ? 1f : 0f, captureOn ? 1f : 0f};
        private final float[] disp = {0f, 0f, 0f};
        private final Map<String, Float> hoverAnim = new HashMap<>();

        private String query = "";
        private boolean searchFocused = false;
        private int filter = 0;
        private String toast = null;
        private long toastUntil = 0;

        // układ wspólny
        private int pw, px, py, totalH, contentTop, contentBottom;
        // lista
        private int statsY, toolbarY, listY, listH, rows, maxScroll, rowW, footY, searchW;
        private final int[] chipX = new int[3];
        private final int[] chipW = new int[3];
        private int allX, allW, clearX, clearW;
        private String allLabel = "";
        private List<Entry> cur = new ArrayList<>();
        private int pending = 0, done = 0;
        // szczegóły
        private int bottomY, boxY, boxH, viewY, viewH;
        private int dSendX, dSendW, dCopyX, dCopyW, dDelX, dDelW;
        private List<OrderedText> wrapped = new ArrayList<>();

        public TpaScreen() {
            super(Text.literal("TPA Menu"));
        }

        // ---------- narzędzia rysowania ----------

        private int c(int argb) {
            int a = (argb >>> 24) & 0xFF;
            int na = Math.max(4, Math.min(255, Math.round(a * fade)));
            return (na << 24) | (argb & 0x00FFFFFF);
        }

        private static int mix(int a, int b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            int r = Math.round(ar + (br - ar) * t);
            int g = Math.round(ag + (bg - ag) * t);
            int bl = Math.round(ab + (bb - ab) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }

        private void rrect(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
            int col = c(color);
            r = Math.min(r, Math.min(w, h) / 2);
            if (r <= 0) {
                ctx.fill(x, y, x + w, y + h, col);
                return;
            }
            for (int i = 0; i < r; i++) {
                double dy = r - i - 0.5;
                int inset = r - (int) Math.round(Math.sqrt(r * (double) r - dy * dy));
                ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, col);
                ctx.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, col);
            }
            ctx.fill(x, y + r, x + w, y + h - r, col);
        }

        private void rbox(DrawContext ctx, int x, int y, int w, int h, int r, int fill, int border) {
            rrect(ctx, x, y, w, h, r, border);
            rrect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
        }

        private void text(DrawContext ctx, String s, int x, int y, int color) {
            ctx.drawText(this.textRenderer, s, x, y, c(color), false);
        }

        private void bold(DrawContext ctx, String s, int x, int y, int color) {
            ctx.drawText(this.textRenderer, Text.literal(s).formatted(Formatting.BOLD), x, y, c(color), false);
        }

        private void centered(DrawContext ctx, String s, int cx, int y, int color) {
            text(ctx, s, cx - this.textRenderer.getWidth(s) / 2, y, color);
        }

        private void big(DrawContext ctx, String s, int x, int y, float scale, int color) {
            MatrixStack m = ctx.getMatrices();
            m.push();
            m.scale(scale, scale, 1f);
            ctx.drawText(this.textRenderer, Text.literal(s).formatted(Formatting.BOLD),
                    Math.round(x / scale), Math.round(y / scale), c(color), false);
            m.pop();
        }

        private static boolean in(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        private void button(DrawContext ctx, int x, int y, int w, int h, String label,
                            int mx, int my, int base, int hover, int textColor) {
            boolean hov = in(mx, my, x, y, w, h);
            rrect(ctx, x, y, w, h, 6, hov ? hover : base);
            centered(ctx, label, x + w / 2, y + (h - 8) / 2, textColor);
        }

        private void sw(DrawContext ctx, int x, int y, float a) {
            rrect(ctx, x, y, 26, 14, 7, mix(BTN_HOVER, GREEN, a));
            rrect(ctx, x + 2 + Math.round(a * 12), y + 2, 10, 10, 5, 0xFFFFFFFF);
        }

        private void segments(DrawContext ctx, int x, int y, String[] labels, int active, int mx, int my) {
            for (int i = 0; i < labels.length; i++) {
                int sx = x + i * 38;
                boolean act = i == active;
                boolean hov = in(mx, my, sx, y, 36, 18);
                if (act) {
                    rbox(ctx, sx, y, 36, 18, 6, ACCENT_SOFT, ACCENT);
                } else {
                    rrect(ctx, sx, y, 36, 18, 6, hov ? BTN_HOVER : BTN);
                }
                centered(ctx, labels[i], sx + 18, y + 5, act ? ACCENT_TEXT : MUTED);
            }
        }

        private String ago(Entry e) {
            long s = (System.currentTimeMillis() - e.time) / 1000;
            return s < 60 ? s + " s temu" : (s / 60) + " min temu";
        }

        // ---------- układ ----------

        private void setView(int v) {
            view = v;
            scrollTarget = 0;
            scrollPos = 0;
            searchFocused = false;
        }

        private void layout() {
            List<Entry> all = activeEntries();
            pending = 0;
            done = 0;
            for (Entry e : all) {
                if (e.checked) done++; else pending++;
            }

            cur = new ArrayList<>();
            String q = query.toLowerCase(Locale.ROOT);
            for (Entry e : all) {
                if (filter == 1 && e.checked) continue;
                if (filter == 2 && !e.checked) continue;
                if (!q.isEmpty() && !e.nick.toLowerCase(Locale.ROOT).contains(q)) continue;
                cur.add(e);
            }

            if (view == V_DETAIL && (detail == null || REQUESTS.get(detail.nick) != detail)) {
                if (detail != null && REQUESTS.get(detail.nick) != null) {
                    detail = REQUESTS.get(detail.nick);
                } else {
                    setView(V_LIST);
                }
            }

            pw = Math.min(440, this.width - 20);
            px = (this.width - pw) / 2;

            int fixedTop = 137;
            int fixedBottom = 40;
            int availList = this.height - fixedTop - fixedBottom - 16;
            rows = Math.max(3, Math.min(MAX_ROWS, availList / ROW_PITCH));
            listH = rows * ROW_PITCH - 4;
            totalH = fixedTop + listH + fixedBottom;
            py = Math.max(4, (this.height - totalH) / 2) + slide;

            contentTop = py + 53;
            contentBottom = py + totalH - 12;

            statsY = py + 53;
            toolbarY = py + 105;
            listY = py + 137;
            footY = listY + listH + 8;

            if (view == V_LIST) {
                maxScroll = Math.max(0, cur.size() * ROW_PITCH - 4 - listH);
            }
            rowW = pw - 24 - (view == V_LIST && maxScroll > 0 ? 10 : 0);

            int total = 0;
            for (int i = 0; i < 3; i++) {
                chipW[i] = this.textRenderer.getWidth(CHIPS[i]) + 16;
                total += chipW[i] + (i > 0 ? 4 : 0);
            }
            int cx = px + pw - 12 - total;
            for (int i = 0; i < 3; i++) {
                chipX[i] = cx;
                cx += chipW[i] + 4;
            }
            searchW = (px + pw - 12 - total) - 8 - (px + 12);

            int queue = SEND_QUEUE.size();
            allLabel = queue > 0 ? "Anuluj kolejkę (" + queue + ")" : "Sprawdź wszystkich";
            allW = this.textRenderer.getWidth(allLabel) + 22;
            allX = px + pw - 12 - allW;
            clearW = this.textRenderer.getWidth("Wyczyść") + 22;
            clearX = allX - 6 - clearW;

            // szczegóły
            bottomY = contentBottom - 22;
            dSendX = px + 12;
            dSendW = 116;
            dCopyX = dSendX + dSendW + 6;
            dCopyW = 70;
            dDelW = 108;
            dDelX = px + pw - 12 - dDelW;
            boxY = contentTop + 90;
            boxH = bottomY - 8 - boxY;
            viewY = boxY + 22;
            viewH = Math.max(10, boxH - 28);

            if (view == V_DETAIL && detail != null) {
                wrapped = new ArrayList<>();
                for (String s : detail.lines) {
                    wrapped.addAll(this.textRenderer.wrapLines(Text.literal(s), pw - 24 - 20));
                }
                maxScroll = Math.max(0, wrapped.size() * 10 - viewH);
            } else if (view == V_SETTINGS) {
                maxScroll = 0;
            }

            scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
            scrollPos = Math.max(0, Math.min(scrollPos, maxScroll));
        }

        // ---------- render ----------

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            long nowN = System.nanoTime();
            float dt = Math.min(0.1f, (nowN - lastNano) / 1_000_000_000f);
            lastNano = nowN;
            float k = 1f - (float) Math.exp(-dt * 16f);

            float t = Math.min(1f, (System.currentTimeMillis() - openStart) / 220f);
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            fade = ease;
            slide = (int) ((1f - ease) * 14);

            super.render(ctx, mouseX, mouseY, delta);
            ctx.fill(0, 0, this.width, this.height, c(0x55000000));
            layout();

            boolean[] flags = {autoCheck, notifyOn, closeAfter, captureOn};
            for (int i = 0; i < 4; i++) {
                toggleAnim[i] += ((flags[i] ? 1f : 0f) - toggleAnim[i]) * k;
            }
            scrollPos += (scrollTarget - scrollPos) * k;
            if (Math.abs(scrollTarget - scrollPos) < 0.3f) scrollPos = scrollTarget;

            // cień i panel
            for (int i = 6; i >= 1; i--) {
                rrect(ctx, px - i, py - i + 3, pw + 2 * i, totalH + 2 * i, 10 + i, 0x0C000000);
            }
            rbox(ctx, px, py, pw, totalH, 10, PANEL_BG, PANEL_BORDER);

            drawHeader(ctx, mouseX, mouseY);

            if (view == V_LIST) {
                drawList(ctx, mouseX, mouseY, k);
            } else if (view == V_DETAIL) {
                drawDetail(ctx, mouseX, mouseY);
            } else {
                drawSettings(ctx, mouseX, mouseY);
            }

            if (toast != null) {
                long left = toastUntil - System.currentTimeMillis();
                if (left <= 0) {
                    toast = null;
                } else {
                    int tw = this.textRenderer.getWidth(toast) + 24;
                    int tx = px + pw / 2 - tw / 2;
                    int ty = contentBottom - 34;
                    rbox(ctx, tx, ty, tw, 22, 8, 0xFF1F2A24, GREEN);
                    centered(ctx, toast, px + pw / 2, ty + 7, GREEN);
                }
            }
        }

        private void drawHeader(DrawContext ctx, int mouseX, int mouseY) {
            rrect(ctx, px + 12, py + 10, 24, 24, 6, ACCENT);
            centered(ctx, "T", px + 24, py + 18, 0xFFFFFFFF);
            String title = view == V_DETAIL ? "Szczegóły gracza" : view == V_SETTINGS ? "Ustawienia" : "TPA Menu";
            String sub = view == V_DETAIL ? "Wynik komendy /stats"
                    : view == V_SETTINGS ? "Zachowanie moda" : "Gracze teleportujący się do Ciebie";
            bold(ctx, title, px + 44, py + 10, TEXT);
            text(ctx, sub, px + 44, py + 23, MUTED);

            boolean closeHov = in(mouseX, mouseY, px + pw - 32, py + 11, 22, 22);
            rrect(ctx, px + pw - 32, py + 11, 22, 22, 6, closeHov ? RED_SOFT : BTN);
            centered(ctx, "x", px + pw - 21, py + 18, closeHov ? RED : MUTED);

            boolean setActive = view == V_SETTINGS;
            int setW = 50;
            int setX = px + pw - 32 - 6 - setW;
            if (setActive) {
                rbox(ctx, setX, py + 11, setW, 22, 6, ACCENT_SOFT, ACCENT);
            } else {
                rrect(ctx, setX, py + 11, setW, 22, 6, in(mouseX, mouseY, setX, py + 11, setW, 22) ? BTN_HOVER : BTN);
            }
            centered(ctx, "Opcje", setX + setW / 2, py + 18, setActive ? ACCENT_TEXT : MUTED);

            ctx.fill(px + 1, py + 44, px + pw - 1, py + 45, c(PANEL_BORDER));
        }

        // ---------- widok: lista ----------

        private void drawList(DrawContext ctx, int mouseX, int mouseY, float k) {
            MinecraftClient mc = MinecraftClient.getInstance();

            int cw = (pw - 24 - 16) / 3;
            String[] labels = {"Oczekujące", "Sprawdzone", "W kolejce"};
            int[] values = {pending, done, SEND_QUEUE.size()};
            int[] cols = {ACCENT, GREEN, AMBER};
            for (int i = 0; i < 3; i++) {
                disp[i] += (values[i] - disp[i]) * k;
                if (Math.abs(values[i] - disp[i]) < 0.05f) disp[i] = values[i];
                int cx = px + 12 + i * (cw + 8);
                rbox(ctx, cx, statsY, cw, 44, 8, CARD, CARD_BORDER);
                rrect(ctx, cx + 10, statsY + 10, 3, 24, 1, cols[i]);
                big(ctx, String.valueOf(Math.round(disp[i])), cx + 20, statsY + 8, 1.7f, TEXT);
                text(ctx, labels[i], cx + 20, statsY + 30, MUTED);
            }

            int sx = px + 12;
            rbox(ctx, sx, toolbarY, searchW, 24, 6, INPUT, searchFocused ? ACCENT : CARD_BORDER);
            int avail = searchW - 16;
            if (query.isEmpty() && !searchFocused) {
                text(ctx, "Szukaj gracza...", sx + 8, toolbarY + 8, MUTED);
            } else {
                String shown = this.textRenderer.trimToWidth(query, avail, true);
                boolean blink = searchFocused && (System.currentTimeMillis() / 500) % 2 == 0;
                text(ctx, shown + (blink ? "_" : ""), sx + 8, toolbarY + 8, TEXT);
            }
            for (int i = 0; i < 3; i++) {
                boolean active = filter == i;
                boolean hov = in(mouseX, mouseY, chipX[i], toolbarY, chipW[i], 24);
                if (active) {
                    rbox(ctx, chipX[i], toolbarY, chipW[i], 24, 12, ACCENT_SOFT, ACCENT);
                } else {
                    rrect(ctx, chipX[i], toolbarY, chipW[i], 24, 12, hov ? BTN_HOVER : BTN);
                }
                centered(ctx, CHIPS[i], chipX[i] + chipW[i] / 2, toolbarY + 8, active ? ACCENT_TEXT : MUTED);
            }

            if (cur.isEmpty()) {
                int cy = listY + listH / 2;
                boolean filtered = !query.isEmpty() || filter != 0;
                String head = filtered ? "Brak wyników" : "Brak próśb o teleport";
                rbox(ctx, px + pw / 2 - 18, cy - 38, 36, 36, 18, CARD, CARD_BORDER);
                centered(ctx, "?", px + pw / 2, cy - 24, MUTED);
                bold(ctx, head, px + pw / 2 - this.textRenderer.getWidth(head) / 2, cy + 4, TEXT);
                centered(ctx, filtered ? "Zmień filtr lub wyszukiwanie" : "Gdy ktoś wyśle /tpa, pojawi się tutaj",
                        px + pw / 2, cy + 17, MUTED);
            } else {
                ctx.enableScissor(px, listY, px + pw, listY + listH);
                float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 280.0);
                int rx = px + 12;

                for (int i = 0; i < cur.size(); i++) {
                    Entry e = cur.get(i);
                    int ry = Math.round(listY + i * ROW_PITCH - scrollPos);
                    if (ry + ROW_H < listY || ry > listY + listH) continue;

                    boolean hovered = mouseY >= listY && mouseY < listY + listH
                            && in(mouseX, mouseY, rx, ry, rowW, ROW_H);
                    float hv = hoverAnim.getOrDefault(e.nick, 0f);
                    hv += ((hovered ? 1f : 0f) - hv) * k;
                    hoverAnim.put(e.nick, hv);

                    boolean selected = e.nick.equals(selNick);
                    rbox(ctx, rx, ry, rowW, ROW_H, 8, mix(ROW, ROW_HOVER, hv), selected ? ACCENT : CARD_BORDER);
                    if (!e.checked) rrect(ctx, rx + 1, ry + 9, 3, ROW_H - 18, 1, ACCENT);

                    rrect(ctx, rx + 11, ry + 5, 30, 30, 6, CARD_BORDER);
                    PlayerListEntry pe = mc.getNetworkHandler() != null
                            ? mc.getNetworkHandler().getPlayerListEntry(e.nick) : null;
                    if (pe != null) {
                        PlayerSkinDrawer.draw(ctx, pe.getSkinTextures(), rx + 13, ry + 7, 26);
                    } else {
                        rrect(ctx, rx + 13, ry + 7, 26, 26, 4, 0xFF3A3F4B);
                    }
                    int dot = e.checked ? GREEN : mix(ACCENT, TEXT, pulse * 0.6f);
                    rrect(ctx, rx + 35, ry + 29, 10, 10, 5, PANEL_BG);
                    rrect(ctx, rx + 37, ry + 31, 6, 6, 3, dot);

                    bold(ctx, e.nick, rx + 52, ry + 8, TEXT);
                    String tm = ago(e);
                    text(ctx, tm, rx + 52, ry + 22, MUTED);
                    String badge = e.checked ? "SPRAWDZONY" : "NOWY";
                    int bw = this.textRenderer.getWidth(badge) + 10;
                    int bx = rx + 52 + this.textRenderer.getWidth(tm) + 8;
                    rrect(ctx, bx, ry + 20, bw, 12, 6, e.checked ? GREEN_SOFT : ACCENT_SOFT);
                    text(ctx, badge, bx + 5, ry + 22, e.checked ? GREEN : ACCENT_TEXT);

                    int delX = rx + rowW - 30;
                    int chkX = delX - 6 - 72;
                    button(ctx, chkX, ry + 9, 72, 22, e.checked ? "Ponów" : "Sprawdź",
                            mouseX, mouseY, e.checked ? BTN : ACCENT, e.checked ? BTN_HOVER : ACCENT_HOVER,
                            0xFFFFFFFF);
                    boolean delHov = in(mouseX, mouseY, delX, ry + 9, 22, 22);
                    rrect(ctx, delX, ry + 9, 22, 22, 6, delHov ? RED_SOFT : BTN);
                    centered(ctx, "x", delX + 11, ry + 16, delHov ? RED : MUTED);
                }
                ctx.disableScissor();

                if (maxScroll > 0) {
                    int tx = px + pw - 10;
                    rrect(ctx, tx, listY, 4, listH, 2, CARD_BORDER);
                    int thumbH = Math.max(14, listH * listH / (cur.size() * ROW_PITCH - 4));
                    int thumbY = listY + Math.round((listH - thumbH) * (scrollPos / maxScroll));
                    rrect(ctx, tx, thumbY, 4, thumbH, 2, ACCENT);
                }
            }

            text(ctx, "Strzałki + Enter: szczegóły", px + 12, footY + 7, MUTED);
            button(ctx, clearX, footY, clearW, 22, "Wyczyść", mouseX, mouseY, BTN, BTN_HOVER, TEXT);
            boolean cancel = !SEND_QUEUE.isEmpty();
            button(ctx, allX, footY, allW, 22, allLabel, mouseX, mouseY,
                    cancel ? AMBER : ACCENT, cancel ? 0xFFFFB84D : ACCENT_HOVER,
                    cancel ? 0xFF1A1200 : 0xFFFFFFFF);
        }

        // ---------- widok: szczegóły ----------

        private void drawDetail(DrawContext ctx, int mouseX, int mouseY) {
            if (detail == null) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            Entry e = detail;

            button(ctx, px + 12, contentTop, 78, 22, "< Wstecz", mouseX, mouseY, BTN, BTN_HOVER, TEXT);

            int cy = contentTop + 30;
            rbox(ctx, px + 12, cy, pw - 24, 52, 8, CARD, CARD_BORDER);
            rrect(ctx, px + 19, cy + 5, 42, 42, 6, CARD_BORDER);
            PlayerListEntry pe = mc.getNetworkHandler() != null
                    ? mc.getNetworkHandler().getPlayerListEntry(e.nick) : null;
            if (pe != null) {
                PlayerSkinDrawer.draw(ctx, pe.getSkinTextures(), px + 20, cy + 6, 40);
            } else {
                rrect(ctx, px + 20, cy + 6, 40, 40, 4, 0xFF3A3F4B);
            }
            big(ctx, e.nick, px + 70, cy + 9, 1.3f, TEXT);
            String tm = ago(e);
            text(ctx, tm, px + 70, cy + 33, MUTED);
            String badge = e.checked ? "SPRAWDZONY" : "NOWY";
            int bw = this.textRenderer.getWidth(badge) + 10;
            int bx = px + 70 + this.textRenderer.getWidth(tm) + 8;
            rrect(ctx, bx, cy + 31, bw, 12, 6, e.checked ? GREEN_SOFT : ACCENT_SOFT);
            text(ctx, badge, bx + 5, cy + 33, e.checked ? GREEN : ACCENT_TEXT);

            rbox(ctx, px + 12, boxY, pw - 24, boxH, 8, INPUT, CARD_BORDER);
            text(ctx, "Odpowiedź serwera na /stats", px + 22, boxY + 8, MUTED);

            if (wrapped.isEmpty()) {
                String msg;
                if (e.waiting) {
                    int dots = 1 + (int) ((System.currentTimeMillis() / 400) % 3);
                    msg = "Oczekiwanie na odpowiedź serwera" + ".".repeat(dots);
                } else if (!captureOn) {
                    msg = "Przechwytywanie wyłączone w opcjach.";
                } else {
                    msg = "Brak danych. Kliknij Wyślij /stats.";
                }
                text(ctx, msg, px + 22, viewY + 4, e.waiting ? ACCENT_TEXT : MUTED);
            } else {
                ctx.enableScissor(px + 14, viewY, px + pw - 14, viewY + viewH);
                int ty = Math.round(viewY - scrollPos);
                for (int i = 0; i < wrapped.size(); i++) {
                    int ly = ty + i * 10;
                    if (ly + 10 < viewY || ly > viewY + viewH) continue;
                    ctx.drawText(this.textRenderer, wrapped.get(i), px + 22, ly, c(TEXT), false);
                }
                ctx.disableScissor();
                if (maxScroll > 0) {
                    int tx = px + pw - 20;
                    rrect(ctx, tx, viewY, 3, viewH, 1, CARD_BORDER);
                    int thumbH = Math.max(12, viewH * viewH / (wrapped.size() * 10));
                    int thumbY = viewY + Math.round((viewH - thumbH) * (scrollPos / maxScroll));
                    rrect(ctx, tx, thumbY, 3, thumbH, 1, ACCENT);
                }
            }

            button(ctx, dSendX, bottomY, dSendW, 22, e.waiting ? "Czekam..." : "Wyślij /stats",
                    mouseX, mouseY, ACCENT, ACCENT_HOVER, 0xFFFFFFFF);
            button(ctx, dCopyX, bottomY, dCopyW, 22, "Kopiuj", mouseX, mouseY, BTN, BTN_HOVER, TEXT);
            button(ctx, dDelX, bottomY, dDelW, 22, "Usuń z listy", mouseX, mouseY, BTN, RED_SOFT, TEXT);
        }

        // ---------- widok: ustawienia ----------

        private int setRowY(int i) {
            return contentTop + 30 + i * 29;
        }

        private void drawSettings(DrawContext ctx, int mouseX, int mouseY) {
            button(ctx, px + 12, contentTop, 78, 22, "< Wstecz", mouseX, mouseY, BTN, BTN_HOVER, TEXT);

            int rx = px + 12;
            int rw = pw - 24;
            for (int i = 0; i < 6; i++) {
                int ry = setRowY(i);
                boolean hov = in(mouseX, mouseY, rx, ry, rw, 26);
                rbox(ctx, rx, ry, rw, 26, 6, hov ? ROW_HOVER : ROW, CARD_BORDER);
                if (i < 4) {
                    text(ctx, SET_LABELS[i], rx + 10, ry + 4, TEXT);
                    text(ctx, SET_DESCS[i], rx + 10, ry + 14, MUTED);
                    sw(ctx, rx + rw - 36, ry + 6, toggleAnim[i]);
                } else if (i == 4) {
                    text(ctx, "Odstęp w kolejce", rx + 10, ry + 4, TEXT);
                    text(ctx, "Przerwa między kolejnymi /stats", rx + 10, ry + 14, MUTED);
                    segments(ctx, rx + rw - 8 - 3 * 38 + 2, ry + 4,
                            new String[]{"1 s", "2 s", "3 s"}, delayIdx, mouseX, mouseY);
                } else {
                    text(ctx, "Czas wygaśnięcia", rx + 10, ry + 4, TEXT);
                    text(ctx, "Po tym czasie gracz znika z listy", rx + 10, ry + 14, MUTED);
                    segments(ctx, rx + rw - 8 - 3 * 38 + 2, ry + 4,
                            new String[]{"1 min", "5 min", "15 min"}, ttlIdx, mouseX, mouseY);
                }
            }
        }

        // ---------- akcje ----------

        private void toast(String s) {
            toast = s;
            toastUntil = System.currentTimeMillis() + 2200;
        }

        private void checkFromList(Entry e) {
            MinecraftClient mc = MinecraftClient.getInstance();
            sendStats(mc, e);
            if (closeAfter) {
                this.close();
            } else if (captureOn) {
                detail = e;
                setView(V_DETAIL);
            } else {
                toast("Wysłano /stats " + e.nick);
            }
        }

        private void moveSel(int d) {
            if (cur.isEmpty()) return;
            int idx = -1;
            for (int i = 0; i < cur.size(); i++) {
                if (cur.get(i).nick.equals(selNick)) { idx = i; break; }
            }
            idx = Math.max(0, Math.min(cur.size() - 1, idx + d));
            selNick = cur.get(idx).nick;
            int top = idx * ROW_PITCH;
            if (top < scrollTarget) scrollTarget = top;
            if (top + ROW_H > scrollTarget + listH) scrollTarget = top + ROW_H - listH;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0) return super.mouseClicked(mx, my, button);
            layout();

            if (in(mx, my, px + pw - 32, py + 11, 22, 22)) {
                this.close();
                return true;
            }
            int setW = 50;
            int setX = px + pw - 32 - 6 - setW;
            if (in(mx, my, setX, py + 11, setW, 22)) {
                setView(view == V_SETTINGS ? V_LIST : V_SETTINGS);
                return true;
            }

            if (view == V_SETTINGS) return clickSettings(mx, my);
            if (view == V_DETAIL) return clickDetail(mx, my);
            return clickList(mx, my, button);
        }

        private boolean clickSettings(double mx, double my) {
            if (in(mx, my, px + 12, contentTop, 78, 22)) {
                setView(V_LIST);
                return true;
            }
            int rx = px + 12;
            int rw = pw - 24;
            for (int i = 0; i < 6; i++) {
                int ry = setRowY(i);
                if (!in(mx, my, rx, ry, rw, 26)) continue;
                if (i < 4) {
                    if (i == 0) autoCheck = !autoCheck;
                    if (i == 1) notifyOn = !notifyOn;
                    if (i == 2) closeAfter = !closeAfter;
                    if (i == 3) captureOn = !captureOn;
                } else {
                    int segStart = rx + rw - 8 - 3 * 38 + 2;
                    for (int s = 0; s < 3; s++) {
                        if (in(mx, my, segStart + s * 38, ry + 4, 36, 18)) {
                            if (i == 4) delayIdx = s; else ttlIdx = s;
                        }
                    }
                }
                saveConfig();
                return true;
            }
            return true;
        }

        private boolean clickDetail(double mx, double my) {
            if (in(mx, my, px + 12, contentTop, 78, 22)) {
                setView(V_LIST);
                return true;
            }
            if (detail == null) return true;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (in(mx, my, dSendX, bottomY, dSendW, 22)) {
                if (!detail.waiting) {
                    sendStats(mc, detail);
                    scrollTarget = 0;
                    toast("Wysłano /stats " + detail.nick);
                }
                return true;
            }
            if (in(mx, my, dCopyX, bottomY, dCopyW, 22)) {
                if (!detail.lines.isEmpty()) {
                    mc.keyboard.setClipboard(String.join("\n", detail.lines));
                    toast("Skopiowano do schowka");
                } else {
                    toast("Brak danych do skopiowania");
                }
                return true;
            }
            if (in(mx, my, dDelX, bottomY, dDelW, 22)) {
                REQUESTS.remove(detail.nick);
                SEND_QUEUE.remove(detail.nick);
                setView(V_LIST);
                return true;
            }
            return true;
        }

        private boolean clickList(double mx, double my, int button) {
            for (int i = 0; i < 3; i++) {
                if (in(mx, my, chipX[i], toolbarY, chipW[i], 24)) {
                    filter = i;
                    scrollTarget = 0;
                    searchFocused = false;
                    return true;
                }
            }

            boolean inSearch = in(mx, my, px + 12, toolbarY, searchW, 24);
            searchFocused = inSearch;
            if (inSearch) return true;

            if (my >= listY && my < listY + listH) {
                int rx = px + 12;
                for (int i = 0; i < cur.size(); i++) {
                    Entry e = cur.get(i);
                    int ry = Math.round(listY + i * ROW_PITCH - scrollPos);
                    if (!in(mx, my, rx, ry, rowW, ROW_H)) continue;
                    int delX = rx + rowW - 30;
                    int chkX = delX - 6 - 72;
                    if (in(mx, my, chkX, ry + 9, 72, 22)) {
                        checkFromList(e);
                    } else if (in(mx, my, delX, ry + 9, 22, 22)) {
                        REQUESTS.remove(e.nick);
                        SEND_QUEUE.remove(e.nick);
                    } else {
                        selNick = e.nick;
                        detail = e;
                        setView(V_DETAIL);
                    }
                    return true;
                }
            }

            if (in(mx, my, clearX, footY, clearW, 22)) {
                REQUESTS.clear();
                SEND_QUEUE.clear();
                toast("Lista wyczyszczona");
                return true;
            }
            if (in(mx, my, allX, footY, allW, 22)) {
                if (!SEND_QUEUE.isEmpty()) {
                    SEND_QUEUE.clear();
                    toast("Kolejka anulowana");
                } else {
                    int added = 0;
                    for (Entry e : cur) {
                        if (!e.checked) {
                            enqueue(e.nick);
                            added++;
                        }
                    }
                    toast(added > 0 ? "Dodano do kolejki: " + added : "Nie ma nowych graczy");
                }
                return true;
            }
            return super.mouseClicked(mx, my, button);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
            layout();
            if (maxScroll > 0 && view != V_SETTINGS) {
                scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (float) vertical * 30f));
                return true;
            }
            return super.mouseScrolled(mx, my, horizontal, vertical);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            if (view == V_LIST && searchFocused && chr >= 32 && chr != 127 && query.length() < 20) {
                query += chr;
                scrollTarget = 0;
                return true;
            }
            return super.charTyped(chr, modifiers);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (searchFocused) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER
                        || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    searchFocused = false;
                } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                    if (!query.isEmpty()) query = query.substring(0, query.length() - 1);
                }
                return true;
            }

            if (view != V_LIST) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                    setView(V_LIST);
                    return true;
                }
            } else {
                layout();
                if (keyCode == GLFW.GLFW_KEY_DOWN) { moveSel(1); return true; }
                if (keyCode == GLFW.GLFW_KEY_UP) { moveSel(-1); return true; }
                if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && selNick != null) {
                    Entry e = REQUESTS.get(selNick);
                    if (e != null) {
                        detail = e;
                        setView(V_DETAIL);
                    }
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_DELETE && selNick != null) {
                    REQUESTS.remove(selNick);
                    SEND_QUEUE.remove(selNick);
                    selNick = null;
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_F && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                    searchFocused = true;
                    return true;
                }
            }

            if (openKey != null && openKey.matchesKey(keyCode, scanCode)) {
                this.close();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }
}
