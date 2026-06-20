package com.aquarius.proxybridge.feature;

import com.aquarius.proxybridge.ProxyBridgeMod;
import com.aquarius.proxybridge.config.Config;
import com.aquarius.proxybridge.net.BridgeNetworking;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Reroutes a whispered command to a registered pearl bot over the bot's HTTP API instead of sending it as in-game
 * chat — so a chat-muted player can still drive their bot. Hooks {@link ClientSendMessageEvents#ALLOW_COMMAND}:
 * when the player runs {@code /<verb> <target> <command>} (msg/tell/w/whisper/…) and {@code <target>} matches a
 * registered bot's IGN (or its short id when no IGN is set), the outgoing command is cancelled and {@code <command>}
 * is sent to that bot's command API — the same path {@code /pb cmd <id>} uses.
 *
 * <p>Only triggers when {@link Config#interceptWhispers} is on AND a registered bot matches the target, so ordinary
 * whispers to real players pass through untouched. It runs on whatever server you're on (the point is to bypass a
 * mute), and always echoes a local "rerouting…" line so the redirection is never silent.
 */
public final class WhisperInterceptor {
    private WhisperInterceptor() {}

    public static void init() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(WhisperInterceptor::onCommand);
    }

    /** @return true to let the command reach the server, false to cancel it (we rerouted it over a backend). */
    private static boolean onCommand(String command) {
        Config config = ProxyBridgeMod.config;
        if (config == null || config.bots.isEmpty()) return true;

        // ALLOW_COMMAND passes the command WITHOUT its leading slash; we need: <verb> <target> <message...>
        String trimmed = command.strip();
        int sp1 = trimmed.indexOf(' ');
        if (sp1 < 0) return true;
        String verb = trimmed.substring(0, sp1);
        if (!isWhisperVerb(config, verb)) return true;

        String rest = trimmed.substring(sp1 + 1).strip();
        int sp2 = rest.indexOf(' ');
        if (sp2 < 0) return true;                                  // a target but no message — let it pass
        String target = rest.substring(0, sp2);
        String message = rest.substring(sp2 + 1).strip();
        if (message.isEmpty()) return true;

        Config.PearlBot bot = matchBot(config, target);
        if (bot == null) return true;                             // not a registered bot — leave the whisper alone

        if (message.startsWith("/")) message = message.substring(1).strip();   // tolerate "/w bot /pearlplus load …"
        if (message.isEmpty()) return true;

        String[] words = message.split("\\s+");

        // goto is coordinate-bearing and must NEVER reach Minecraft chat. Capture it unconditionally (independent of
        // interceptWhispers — this is a privacy guarantee, not a mute bypass): route it over the bot's HTTP API if one
        // is configured, otherwise block the whisper outright so the coords are never sent. Either way, cancel it.
        if (words[0].equalsIgnoreCase("goto")) {
            if (hasApi(bot)) {
                feedback("goto → " + bot.id + " over its API (off-chat)");
                ProxyBridgeMod.runRemoteToChat(bot, message);
            } else {
                feedback("⛔ goto blocked — no API token for " + bot.id + "; coords were NOT sent to chat. "
                    + "Add one with /pb bots add " + bot.id + " <url> <token>.");
            }
            return false;
        }

        // Pearl-pull fast path: "<bot> load [id]" routed over the backend instead of in-game chat. Independent of
        // interceptWhispers (it's a speed-up, not a mute bypass): the instant plugin channel first when connected
        // through the proxy, else the bot's HTTP API ("pearlpull" — self-scoped, pearl.pull-gated). Falls through to
        // a normal in-game whisper if neither backend is available.
        if (config.bridgePull && words[0].equalsIgnoreCase("load")) {
            String pearlId = words.length >= 2 ? words[1] : "";
            if (BridgeNetworking.sendPearlPull(pearlId)) {
                feedback("⚡ instant pull via bridge → " + (pearlId.isBlank() ? "default pearl" : pearlId));
                return false;
            }
            if (hasApi(bot)) {
                String apiCmd = pearlId.isBlank() ? "pearlpull" : "pearlpull " + pearlId;
                feedback("pull via " + bot.id + " API → /" + apiCmd);
                ProxyBridgeMod.runRemoteToChat(bot, apiCmd);
                return false;
            }
            return true;                                           // no backend ready — let the whisper through
        }

        // Muted-safe generic command reroute over the HTTP API (opt-in).
        if (!config.interceptWhispers) return true;
        feedback("muted-safe: rerouting to " + bot.id + " via API → /" + message);
        ProxyBridgeMod.runRemoteToChat(bot, message);
        return false;                                             // cancel the in-game whisper
    }

    private static boolean hasApi(Config.PearlBot bot) {
        return bot.url != null && !bot.url.isBlank() && bot.token != null && !bot.token.isBlank();
    }

    private static boolean isWhisperVerb(Config config, String verb) {
        for (String v : config.whisperVerbs) {
            if (v.equalsIgnoreCase(verb)) return true;
        }
        return false;
    }

    /** Prefer an explicit IGN match; otherwise fall back to a bot whose short id matches and that has no IGN set. */
    private static Config.PearlBot matchBot(Config config, String target) {
        for (Config.PearlBot b : config.bots) {
            if (b.ign != null && !b.ign.isBlank() && b.ign.equalsIgnoreCase(target)) return b;
        }
        for (Config.PearlBot b : config.bots) {
            if ((b.ign == null || b.ign.isBlank()) && b.id.equalsIgnoreCase(target)) return b;
        }
        return null;
    }

    private static void feedback(String msg) {
        var mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[ProxyBridge] " + msg), false);
        });
    }
}
