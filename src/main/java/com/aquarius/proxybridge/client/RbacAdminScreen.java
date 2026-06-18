package com.aquarius.proxybridge.client;

import com.aquarius.proxybridge.config.Config;
import com.aquarius.proxybridge.web.CommandResponse;
import com.aquarius.proxybridge.web.PermsSnapshot;
import com.aquarius.proxybridge.web.WebAPI;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-game RBAC admin screen for one registered bot. Reads the bot's access-control state via {@code perms export}
 * (structured JSON) over the HTTP command API, renders a paginated user table, and drives every change through the
 * same API ({@code perms ...}). Requires an admin token for the bot — {@code perms} is admin-only, so the API simply
 * returns nothing usable otherwise.
 *
 * <p>Layout: top row toggles RBAC + the API and refreshes; a left-column paginated user list (click to select); a
 * right column that is the add-user form plus, when a user is selected, that user's role/preset/token controls.
 * All actions are fired off-thread and the snapshot is re-fetched on completion, so the UI always reflects the bot.
 */
public class RbacAdminScreen extends Screen {

    private static final List<String> ROLE_ORDER = List.of("guest", "user", "operator", "admin");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{40,}");
    private static final int MARGIN = 12;
    private static final int LIST_Y = 54;
    private static final int ROW_H = 20;

    private final Config.PearlBot bot;
    private final Gson gson = new Gson();

    private PermsSnapshot snapshot;
    private int selectedIndex = -1;
    private int page = 0;
    private String status = "Loading…";

    private EditBox newUserBox;
    private String newUserRole = "user";

    public RbacAdminScreen(Config.PearlBot bot) {
        super(Component.literal("RBAC — " + bot.id));
        this.bot = bot;
    }

    @Override
    protected void init() {
        int detailX = MARGIN + 184;

        // --- top controls ---
        boolean enabled = snapshot != null && snapshot.enabled;
        boolean apiOn = snapshot != null && snapshot.api != null && snapshot.api.enabled;
        addRenderableWidget(Button.builder(Component.literal("RBAC: " + (enabled ? "ON" : "off")),
            b -> send("perms enable " + (enabled ? "off" : "on"))).bounds(MARGIN, 28, 90, 18).build());
        addRenderableWidget(Button.builder(Component.literal("API: " + (apiOn ? "ON" : "off")),
            b -> send("perms api " + (apiOn ? "off" : "on"))).bounds(MARGIN + 94, 28, 80, 18).build());
        addRenderableWidget(Button.builder(Component.literal("⟳ Refresh"),
            b -> refresh()).bounds(MARGIN + 178, 28, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Done"),
            b -> onClose()).bounds(this.width - MARGIN - 60, 28, 60, 18).build());

        // --- left column: paginated user list ---
        int rowsPerPage = Math.max(3, Math.min(8, (this.height - LIST_Y - 60) / ROW_H));
        List<PermsSnapshot.User> users = snapshot == null ? List.of() : snapshot.users;
        int totalPages = Math.max(1, (users.size() + rowsPerPage - 1) / rowsPerPage);
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;
        int start = page * rowsPerPage;
        for (int i = start; i < Math.min(users.size(), start + rowsPerPage); i++) {
            PermsSnapshot.User u = users.get(i);
            final int idx = i;
            String label = (i == selectedIndex ? "▸ " : "")
                + u.name + " · " + u.role + (u.tokens > 0 ? " · 🔑" + u.tokens : "");
            addRenderableWidget(Button.builder(Component.literal(trim(label, 26)),
                b -> { selectedIndex = idx; rebuild(); }).bounds(MARGIN, LIST_Y + (i - start) * ROW_H, 170, 18).build());
        }
        if (users.size() > rowsPerPage) {
            int navY = LIST_Y + rowsPerPage * ROW_H;
            addRenderableWidget(Button.builder(Component.literal("◂"),
                b -> { page--; rebuild(); }).bounds(MARGIN, navY, 28, 18).build());
            addRenderableWidget(Button.builder(Component.literal("▸"),
                b -> { page++; rebuild(); }).bounds(MARGIN + 142, navY, 28, 18).build());
        }

        // --- right column: add-user form ---
        newUserBox = new EditBox(this.font, detailX, 54, 150, 18, Component.literal("new username"));
        newUserBox.setHint(Component.literal("new username"));
        newUserBox.setMaxLength(16);
        if (snapshot != null) addRenderableWidget(newUserBox);
        addRenderableWidget(Button.builder(Component.literal("New role: " + newUserRole),
            b -> { newUserRole = nextRole(newUserRole); rebuild(); }).bounds(detailX, 74, 150, 18).build());
        addRenderableWidget(Button.builder(Component.literal("➕ Add user"), b -> {
            String name = newUserBox == null ? "" : newUserBox.getValue().trim();
            if (name.isEmpty()) { status = "Enter a username to add."; rebuild(); return; }
            send("perms user add " + name + " " + newUserRole);
        }).bounds(detailX, 94, 150, 18).build());

        // --- right column: selected-user controls ---
        PermsSnapshot.User sel = selectedUser();
        if (sel != null) {
            int y = 124;
            addRenderableWidget(Button.builder(Component.literal("Role: " + sel.role),
                b -> send("perms user role " + sel.name + " " + nextRole(sel.role))).bounds(detailX, y, 150, 18).build());
            // preset checkboxes — 2 columns
            List<String> groups = snapshot.groups;
            for (int gi = 0; gi < groups.size(); gi++) {
                String g = groups.get(gi);
                boolean granted = sel.grants != null && sel.grants.contains("group." + g);
                int col = gi % 2, rowi = gi / 2;
                int bx = detailX + col * 76, by = y + 22 + rowi * 18;
                addRenderableWidget(Button.builder(Component.literal((granted ? "☑ " : "☐ ") + trim(g, 7)),
                    b -> send("perms user " + (granted ? "ungrant " : "grant ") + sel.name + " group." + g))
                    .bounds(bx, by, 74, 16).build());
            }
            int actionsY = y + 22 + ((groups.size() + 1) / 2) * 18 + 4;
            addRenderableWidget(Button.builder(Component.literal("🎫 Issue token"),
                b -> issueToken(sel.name)).bounds(detailX, actionsY, 150, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Revoke a token (" + sel.tokens + ")"),
                b -> { if (sel.tokens > 0) send("perms token revoke " + sel.name + " 0"); })
                .bounds(detailX, actionsY + 20, 150, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Mode: " + sel.connectMode),
                b -> send("perms user mode " + sel.name + " "
                    + ("spectate".equalsIgnoreCase(sel.connectMode) ? "control" : "spectate")))
                .bounds(detailX, actionsY + 40, 74, 18).build());
            addRenderableWidget(Button.builder(Component.literal("🗑 Remove"),
                b -> { send("perms user remove " + sel.name); selectedIndex = -1; })
                .bounds(detailX + 76, actionsY + 40, 74, 18).build());
        }

        if (snapshot == null) refresh();   // first open
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(this.font, "Access Control — " + bot.id + "  (" + bot.url + ")", MARGIN, 12, 0xFFFFFF);
        if (snapshot != null) {
            String hdr = "min-connect: " + snapshot.minConnectRole + "   users: " + snapshot.users.size()
                + "   api: " + (snapshot.api != null ? snapshot.api.bindHost + ":" + snapshot.api.port : "?");
            g.drawString(this.font, hdr, MARGIN, LIST_Y - 12, 0xA0A0A0);
        }
        PermsSnapshot.User sel = selectedUser();
        if (sel != null) {
            g.drawString(this.font, "Editing " + sel.name, MARGIN + 184, 114, 0xFFFF80);
        }
        g.drawString(this.font, trim(status, 90), MARGIN, this.height - 18, 0xC0C0C0);
    }

    // ---------------------------------------------------------------- networking

    private void refresh() {
        status = "Loading…";
        ForkJoinPool.commonPool().execute(() -> {
            PermsSnapshot snap = null;
            String err = null;
            try {
                CommandResponse resp = WebAPI.INSTANCE.execute("perms export", bot.url, bot.token);
                snap = PermsSnapshot.fromResponse(resp, gson);
                if (snap == null) err = "no snapshot — is the API enabled and the token an admin's?";
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + " " + e.getMessage();
            }
            final PermsSnapshot fsnap = snap;
            final String ferr = err;
            Minecraft.getInstance().execute(() -> {
                if (fsnap != null) {
                    this.snapshot = fsnap;
                    if (selectedIndex >= fsnap.users.size()) selectedIndex = -1;
                    status = "Loaded " + fsnap.users.size() + " user(s)";
                } else {
                    status = "Error: " + ferr;
                }
                rebuild();
            });
        });
    }

    /** Fire a perms command, show its result, then re-fetch the snapshot. */
    private void send(String command) {
        status = "→ " + command;
        ForkJoinPool.commonPool().execute(() -> {
            String msg;
            try {
                CommandResponse resp = WebAPI.INSTANCE.execute(command, bot.url, bot.token);
                msg = resp != null && resp.embed() != null && !resp.embed().isBlank() ? resp.embed() : firstLine(resp);
            } catch (Exception e) {
                msg = "error: " + e.getMessage();
            }
            final String fmsg = msg;
            Minecraft.getInstance().execute(() -> { status = fmsg; refresh(); });
        });
    }

    private void issueToken(String name) {
        status = "issuing token…";
        ForkJoinPool.commonPool().execute(() -> {
            String msg;
            try {
                CommandResponse resp = WebAPI.INSTANCE.execute("perms token issue " + name, bot.url, bot.token);
                String token = extractToken(resp);
                if (token != null) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(token);
                    msg = "Token for " + name + " copied to clipboard (shown once): " + token;
                } else {
                    msg = "Issued, but couldn't read the token back — check the bot.";
                }
            } catch (Exception e) {
                msg = "error: " + e.getMessage();
            }
            final String fmsg = msg;
            Minecraft.getInstance().execute(() -> { status = fmsg; refresh(); });
        });
    }

    // ---------------------------------------------------------------- helpers

    private PermsSnapshot.User selectedUser() {
        if (snapshot == null || selectedIndex < 0 || selectedIndex >= snapshot.users.size()) return null;
        return snapshot.users.get(selectedIndex);
    }

    private void rebuild() {
        if (this.minecraft != null) rebuildWidgets();
    }

    private static String nextRole(String current) {
        int i = ROLE_ORDER.indexOf(current == null ? "" : current.toLowerCase());
        return ROLE_ORDER.get((i + 1) % ROLE_ORDER.size());
    }

    private static String extractToken(CommandResponse resp) {
        if (resp == null || resp.multiLineOutput() == null) return null;
        for (String line : resp.multiLineOutput()) {
            if (line == null) continue;
            Matcher m = TOKEN.matcher(line);
            if (m.find()) return m.group();
        }
        return null;
    }

    private static String firstLine(CommandResponse resp) {
        if (resp != null && resp.multiLineOutput() != null && !resp.multiLineOutput().isEmpty()) {
            return resp.multiLineOutput().get(0);
        }
        return "ok";
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
