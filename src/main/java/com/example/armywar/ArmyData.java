package com.example.armywar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Speichert Armee + XP als JSON in config/armywar.json (versionsunabhängig). */
public class ArmyData {
    public static class Rec { public String army; public int xp; public String role = "infanterie"; }

    public static Map<String, Rec> players = new HashMap<>();
    public static Map<String, String> leaders = new HashMap<>(); // army -> UUID

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static class Store { Map<String, Rec> players = new HashMap<>(); Map<String, String> leaders = new HashMap<>(); }

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("armywar.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Store s = GSON.fromJson(Files.readString(file()), Store.class);
                if (s != null) { players = s.players; leaders = s.leaders; }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void save() {
        try {
            Store s = new Store(); s.players = players; s.leaders = leaders;
            Files.writeString(file(), GSON.toJson(s));
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static Rec get(UUID id) { return players.get(id.toString()); }

    public static int count(String army) {
        int n = 0;
        for (Rec r : players.values()) if (army.equals(r.army)) n++;
        return n;
    }
}
