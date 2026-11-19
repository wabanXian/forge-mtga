package forge.mtga.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.Mastery;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Atomic, coordinator-independent mastery and daily-quest state in data/mastery-offline.json. */
final class OfflineProgressStore {
    static final int BOT_XP = 100;
    static final int EVENT_XP = 10_000;
    private static final int VERSION = 1;
    private static final int SLOTS = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String[] LISTS = {"avatars", "sleeves", "pets", "emotes", "titles"};
    private static final QuestDef[] QUESTS = {
            new QuestDef("PlayVintage", "MatchPlayed_Vintage", 1, 500), new QuestDef("PlayLegacy", "MatchPlayed_Legacy", 1, 500),
            new QuestDef("PlayPauper", "MatchPlayed_Pauper", 2, 500), new QuestDef("PlayPremodern", "MatchPlayed_Premodern", 2, 500),
            new QuestDef("PlayModern", "MatchPlayed_Modern", 2, 500), new QuestDef("PlayPioneer", "MatchPlayed_Pioneer", 2, 500),
            new QuestDef("WinStandard", "MatchWon_Standard", 1, 500), new QuestDef("PlayMomir", "MatchPlayed_Momir", 2, 500),
            new QuestDef("PlayRanked", "RankedPlayed", 2, 750), new QuestDef("PlayDraft", "DraftPlayed", 2, 750),
            new QuestDef("AttackCreatures", "CreaturesAttacked", 20, 500), new QuestDef("CastWhiteSpells", "WhiteSpellsCast", 20, 500),
            new QuestDef("CastBlueSpells", "BlueSpellsCast", 20, 500), new QuestDef("CastBlackSpells", "BlackSpellsCast", 20, 500),
            new QuestDef("CastRedSpells", "RedSpellsCast", 20, 500), new QuestDef("CastGreenSpells", "GreenSpellsCast", 20, 500),
            new QuestDef("CastInstantsSorceries", "InstantsAndSorceriesCast", 15, 500), new QuestDef("CastCreatures", "CreaturesCast", 20, 500),
            new QuestDef("PlayLands", "LandsPlayed", 25, 500), new QuestDef("DealDamage", "DamageDealt", 40, 500),
            new QuestDef("KillCreatures", "OpponentCreaturesDied", 15, 750), new QuestDef("CastArtifactsEnchantments", "ArtifactsAndEnchantmentsCast", 10, 500)
    };

    private final File file;
    private final LongSupplier now;
    private final JsonObject track;
    private JsonObject root;

    OfflineProgressStore(File dataDir) { this(dataDir, System::currentTimeMillis); }

    OfflineProgressStore(File dataDir, LongSupplier now) {
        this.file = new File(dataDir, "mastery-offline.json");
        this.now = now;
        this.track = loadTrack();
        this.root = read();
        normalize();
        refreshQuests();
        write();
    }

    synchronized JsonObject track() { return track.deepCopy(); }
    synchronized int xp() { return root.get("xp").getAsInt(); }
    synchronized int level() { return xp() / Mastery.LEVEL_XP; }
    synchronized int claimedLevel() { return root.get("claimedLevel").getAsInt(); }
    synchronized JsonObject owned() { return root.getAsJsonObject("owned").deepCopy(); }

    synchronized void rememberOwnership(JsonObject owned) {
        if (root.has("ownershipBackup")) return;
        root.add("ownershipBackup", owned.deepCopy());
        write();
        System.out.println("[mtga] mastery offline: backed up previous cosmetic ownership in " + file.getName());
    }

    synchronized void claimed(int level) {
        root.addProperty("claimedLevel", Math.max(claimedLevel(), Math.min(level, level())));
        write();
    }

    synchronized int botMatch(String matchId) { return award("matchAwards", safe(matchId), BOT_XP); }
    synchronized int eventReward(String event) {
        if (!("archenemy_1".equals(event) || "archenemy_2".equals(event) || "archenemy_3".equals(event))) return 0;
        return award("eventAwards", event, EVENT_XP);
    }

    private int award(String setName, String key, int amount) {
        JsonObject set = root.getAsJsonObject(setName);
        if (set.has(key)) return 0;
        set.addProperty(key, now.getAsLong());
        addXp(amount);
        write();
        return amount;
    }

    synchronized JsonArray quests() {
        refreshQuests();
        return questJson();
    }

    synchronized boolean swap(String questId) {
        refreshQuests();
        int today = day();
        JsonObject q = findQuest(questId);
        if (q == null || q.has("doneDay") || q.get("swapDay").getAsInt() == today) return false;
        Set<String> taken = questKinds(q);
        QuestDef def = pick(taken, questId.hashCode() ^ today);
        fill(q, q.get("slot").getAsInt(), def, today);
        q.addProperty("swapDay", today);
        write();
        return true;
    }

    /** Quest progress and its XP are committed in the same atomic file replacement. */
    synchronized JsonArray progress(String matchId, JsonObject metrics) {
        refreshQuests();
        JsonArray updates = new JsonArray();
        if (metrics == null) return updates;
        String key = safe(matchId);
        JsonObject events = root.getAsJsonObject("questEvents");
        if (events.has(key)) return updates;
        events.addProperty(key, now.getAsLong());
        int today = day();
        for (JsonElement e : root.getAsJsonArray("quests")) {
            JsonObject q = e.getAsJsonObject();
            if (q.has("doneDay")) continue;
            QuestDef def = definition(q.get("kind").getAsString());
            int n = def == null || !metrics.has(def.metric) ? 0 : Math.max(0, metrics.get(def.metric).getAsInt());
            if (n == 0) continue;
            int from = q.get("progress").getAsInt(), to = Math.min(q.get("goal").getAsInt(), from + n);
            q.addProperty("progress", to);
            JsonObject u = new JsonObject();
            u.addProperty("questId", q.get("questId").getAsString()); u.addProperty("kind", q.get("kind").getAsString());
            u.addProperty("goal", q.get("goal").getAsInt()); u.addProperty("from", from); u.addProperty("to", to);
            if (to >= q.get("goal").getAsInt()) {
                q.addProperty("doneDay", today);
                int reward = q.get("xp").getAsInt();
                u.addProperty("xp", reward);
                addXp(reward);
            }
            updates.add(u);
        }
        write();
        return updates;
    }

    private void addXp(int amount) {
        int oldLevel = level();
        root.addProperty("xp", xp() + amount);
        int newLevel = level();
        JsonArray items = track.getAsJsonArray("items");
        JsonObject owned = root.getAsJsonObject("owned");
        for (int level = oldLevel + 1; level <= newLevel; level++) {
            int start = (level - 1) * 2;
            for (int i = start; i < Math.min(start + 2, items.size()); i++) {
                JsonArray item = items.get(i).getAsJsonArray();
                String list = listOf(item.get(0).getAsString()), id = item.get(1).getAsString();
                JsonArray a = owned.getAsJsonArray(list);
                boolean found = false; for (JsonElement x : a) found |= id.equals(x.getAsString());
                if (!found) a.add(id);
            }
        }
    }

    private void refreshQuests() {
        int today = day();
        JsonArray qs = root.getAsJsonArray("quests");
        Set<String> taken = questKinds(null);
        for (int slot = 0; slot < SLOTS; slot++) {
            JsonObject q = null;
            for (JsonElement e : qs) if (e.getAsJsonObject().get("slot").getAsInt() == slot) q = e.getAsJsonObject();
            boolean stale = q == null || (q.has("doneDay") && q.get("doneDay").getAsInt() < today);
            if (!stale) continue;
            if (q != null) taken.remove(q.get("kind").getAsString());
            QuestDef def = pick(taken, slot * 7919 ^ today);
            if (q == null) { q = new JsonObject(); qs.add(q); }
            fill(q, slot, def, today);
            taken.add(def.id);
        }
    }

    private JsonArray questJson() {
        JsonArray out = new JsonArray(); int today = day();
        List<JsonObject> sorted = new ArrayList<>(); for (JsonElement e : root.getAsJsonArray("quests")) sorted.add(e.getAsJsonObject());
        sorted.sort(java.util.Comparator.comparingInt(q -> q.get("slot").getAsInt()));
        for (JsonObject q : sorted) {
            JsonObject x = new JsonObject();
            for (String k : new String[]{"questId", "kind", "goal", "progress", "xp"}) x.add(k, q.get(k));
            x.addProperty("done", q.has("doneDay")); x.addProperty("canSwap", !q.has("doneDay") && q.get("swapDay").getAsInt() != today);
            out.add(x);
        }
        return out;
    }

    private void normalize() {
        root.addProperty("version", VERSION);
        if (!root.has("xp")) root.addProperty("xp", 0);
        if (!root.has("claimedLevel")) root.addProperty("claimedLevel", 0);
        for (String k : new String[]{"matchAwards", "eventAwards", "questEvents"}) if (!root.has(k)) root.add(k, new JsonObject());
        if (!root.has("quests")) root.add("quests", new JsonArray());
        if (!root.has("owned")) root.add("owned", new JsonObject());
        JsonObject owned = root.getAsJsonObject("owned");
        for (String k : LISTS) if (!owned.has(k)) owned.add(k, new JsonArray());
    }

    private JsonObject read() {
        if (!file.isFile()) return new JsonObject();
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("version") || o.get("version").getAsInt() > VERSION) throw new IOException("unsupported version");
            return o;
        } catch (Exception e) {
            File bad = new File(file.getParentFile(), file.getName() + ".bad-" + now.getAsLong());
            try { Files.copy(file.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException copy) { throw new IllegalStateException("Cannot back up damaged offline mastery " + file + ": " + copy, copy); }
            System.out.println("[mtga] mastery offline: ERROR unreadable save; backed up to " + bad.getName() + ": " + e);
            throw new IllegalStateException("Offline mastery save is damaged; backup: " + bad, e);
        }
    }

    private void write() {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException("Cannot write offline mastery " + file, e); }
    }

    private static JsonObject loadTrack() {
        try (InputStream in = OfflineProgressStore.class.getResourceAsStream("/forge/mtga/mastery-track.json")) {
            if (in == null) throw new IOException("resource missing");
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("version") || !o.has("items") || o.getAsJsonArray("items").size() == 0) throw new IOException("invalid resource");
            return o;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Offline mastery reward table cannot be loaded", e); }
    }

    private int day() { return (int)Math.floorDiv(now.getAsLong(), 86_400_000L); }
    private static String safe(String s) { return s == null ? "" : s.substring(0, Math.min(80, s.length())); }
    private JsonObject findQuest(String id) { for (JsonElement e : root.getAsJsonArray("quests")) if (e.getAsJsonObject().get("questId").getAsString().equals(id)) return e.getAsJsonObject(); return null; }
    private Set<String> questKinds(JsonObject except) { Set<String> s = new HashSet<>(); for (JsonElement e : root.getAsJsonArray("quests")) if (e.getAsJsonObject() != except) s.add(e.getAsJsonObject().get("kind").getAsString()); return s; }
    private static QuestDef definition(String id) { for (QuestDef q : QUESTS) if (q.id.equals(id)) return q; return null; }
    private static QuestDef pick(Set<String> taken, int seed) { List<QuestDef> free = new ArrayList<>(); for (QuestDef q : QUESTS) if (!taken.contains(q.id)) free.add(q); return free.get(Math.floorMod(seed, free.size())); }
    private static void fill(JsonObject q, int slot, QuestDef d, int today) { q.entrySet().clear(); q.addProperty("slot", slot); q.addProperty("questId", UUID.randomUUID().toString()); q.addProperty("kind", d.id); q.addProperty("goal", d.goal); q.addProperty("progress", 0); q.addProperty("xp", d.xp); q.addProperty("assignedDay", today); q.addProperty("swapDay", -1); }
    private static String listOf(String kind) { return switch (kind) { case "avatar" -> "avatars"; case "sleeve" -> "sleeves"; case "pet" -> "pets"; case "title" -> "titles"; default -> "emotes"; }; }
    private record QuestDef(String id, String metric, int goal, int xp) { }
}
