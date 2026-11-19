package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.FileProfileStore;
import forge.mtga.social.MasteryService;
import forge.mtga.social.QuestService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Headless regression check for the coordinator-independent pass, quests, claims and persistence. */
public final class OfflineMasteryCheck {
    private static int failed;
    private static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) failed++; }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("mtga-offline-mastery-").toFile();
        try {
            FileProfileStore profiles = new FileProfileStore(dir);
            profiles.update(p -> p.owned.all = true); // legacy offline mode: must be backed up before conversion
            MasteryService mastery = new MasteryService(null, profiles, dir);
            QuestService quests = new QuestService(null, mastery); quests.refresh();
            MasteryTrack track = new MasteryTrack(mastery);
            check(mastery.xp() == 0 && mastery.level() == 0 && track.enabled(), "new offline save starts at level 0 with the native track enabled");
            JsonObject raw = JsonParser.parseString(Files.readString(new File(dir, "mastery-offline.json").toPath())).getAsJsonObject();
            check(raw.has("ownershipBackup") && !profiles.load().owned.all, "legacy owned.all is backed up before earned ownership replaces it");

            check(mastery.botMatch("forge-check-1") == 100 && mastery.xp() == 100, "first bot match awards 100 XP");
            check(mastery.botMatch("forge-check-1") == 0 && mastery.xp() == 100, "same match id is deduplicated");
            for (int i = 2; i <= 10; i++) mastery.botMatch("forge-check-" + i);
            check(mastery.xp() == 1_000 && mastery.level() == 1, "ten bot matches reach level 1");
            check(ownedCount(profiles) == 2, "level 1 grants both free and premium cosmetics");

            JsonArray nodes = new JsonArray(); nodes.add("R1");
            check(track.claim(nodes) != null && mastery.claimedLevel() == 1, "GraphProcessV2 reward claim is recorded");
            check(track.claim(nodes) == null, "claimed reward is not emitted twice");
            check(mastery.eventReward("archenemy_1") == 10_000 && mastery.eventReward("archenemy_1") == 0, "one-time event reward is deduplicated");

            JsonArray before = quests.quests();
            check(before.size() == 3, "offline quests fill three slots");
            String swap = before.get(0).getAsJsonObject().get("questId").getAsString();
            check(quests.swap(swap) && !quests.swap(quests.quests().get(0).getAsJsonObject().get("questId").getAsString()), "a quest can be replaced only once per UTC day");
            int questXpBefore = mastery.xp();
            JsonObject metrics = allMetrics();
            JsonArray updates = quests.progress("forge-quest-1", metrics);
            int questXp = 0; for (JsonElement e : updates) if (e.getAsJsonObject().has("xp")) questXp += e.getAsJsonObject().get("xp").getAsInt();
            check(updates.size() == 3 && questXp > 0 && mastery.xp() == questXpBefore + questXp, "real match metrics finish quests and atomically award their XP");
            check(quests.progress("forge-quest-1", metrics).size() == 0 && mastery.xp() == questXpBefore + questXp, "quest match report is deduplicated");

            int savedXp = mastery.xp(), savedClaim = mastery.claimedLevel(), savedOwned = ownedCount(profiles);
            MasteryService restarted = new MasteryService(null, profiles, dir);
            QuestService restartedQuests = new QuestService(null, restarted); restartedQuests.refresh();
            check(restarted.xp() == savedXp && restarted.claimedLevel() == savedClaim && ownedCount(profiles) == savedOwned,
                    "restart preserves XP, claims and ownership");
            check(restarted.botMatch("forge-check-1") == 0 && restarted.eventReward("archenemy_1") == 0
                    && restartedQuests.progress("forge-quest-1", metrics).size() == 0, "restart preserves every award deduplication key");

            File save = new File(dir, "mastery-offline.json");
            JsonObject doc = JsonParser.parseString(Files.readString(save.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            int today = (int)Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
            JsonObject oldDone = doc.getAsJsonArray("quests").get(0).getAsJsonObject(); oldDone.addProperty("doneDay", today - 1);
            JsonObject unfinished = doc.getAsJsonArray("quests").get(1).getAsJsonObject(); unfinished.remove("doneDay"); unfinished.addProperty("progress", 1);
            JsonObject todayDone = doc.getAsJsonArray("quests").get(2).getAsJsonObject(); todayDone.addProperty("doneDay", today);
            String unfinishedId = unfinished.get("questId").getAsString(), todayDoneId = todayDone.get("questId").getAsString();
            Files.writeString(save.toPath(), doc.toString(), StandardCharsets.UTF_8);
            MasteryService nextDay = new MasteryService(null, profiles, dir);
            QuestService nextQuests = new QuestService(null, nextDay); nextQuests.refresh();
            boolean refreshed = nextQuests.quests().size() == 3, keptUnfinished = false, keptTodayDone = false;
            for (JsonElement e : nextQuests.quests()) {
                JsonObject q = e.getAsJsonObject();
                if (q.get("questId").getAsString().equals(unfinishedId)) keptUnfinished = q.get("progress").getAsInt() == 1 && !q.get("done").getAsBoolean();
                if (q.get("questId").getAsString().equals(todayDoneId)) keptTodayDone = q.get("done").getAsBoolean();
            }
            check(refreshed && keptUnfinished && keptTodayDone, "UTC refresh replaces only quests completed before today and retains unfinished/current-day slots");

            File damagedDir = new File(dir, "damaged"); damagedDir.mkdirs();
            Files.writeString(new File(damagedDir, "mastery-offline.json").toPath(), "{broken", StandardCharsets.UTF_8);
            boolean refused = false;
            try { new MasteryService(null, new FileProfileStore(damagedDir), damagedDir); } catch (IllegalStateException expected) { refused = true; }
            File[] backups = damagedDir.listFiles((d, n) -> n.startsWith("mastery-offline.json.bad-"));
            check(refused && backups != null && backups.length == 1, "damaged save is backed up and startup refuses to silently reset progress");
        } finally { delete(dir); }
        System.out.println(failed == 0 ? "OFFLINE MASTERY CHECK OK" : failed + " FAILED");
        if (failed != 0) System.exit(1);
    }

    private static int ownedCount(FileProfileStore store) {
        var o = store.load().owned;
        return o.avatars.size() + o.sleeves.size() + o.pets.size() + o.emotes.size() + o.titles.size();
    }
    private static JsonObject allMetrics() {
        JsonObject m = new JsonObject();
        for (String k : new String[]{"MatchPlayed_Vintage","MatchPlayed_Legacy","MatchPlayed_Pauper","MatchPlayed_Premodern","MatchPlayed_Modern","MatchPlayed_Pioneer","MatchWon_Standard","MatchPlayed_Momir","RankedPlayed","DraftPlayed","CreaturesAttacked","WhiteSpellsCast","BlueSpellsCast","BlackSpellsCast","RedSpellsCast","GreenSpellsCast","InstantsAndSorceriesCast","CreaturesCast","LandsPlayed","DamageDealt","OpponentCreaturesDied","ArtifactsAndEnchantmentsCast"}) m.addProperty(k, 100);
        return m;
    }
    private static void delete(File f) { if (f.isDirectory()) { File[] cs = f.listFiles(); if (cs != null) for (File c : cs) delete(c); } f.delete(); }
}
