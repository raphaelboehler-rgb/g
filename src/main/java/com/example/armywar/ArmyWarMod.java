package com.example.armywar;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod(ArmyWarMod.MODID)
public class ArmyWarMod {
    public static final String MODID = "armywar";
    static final String[] ARMIES = {"rot", "blau"};
    static final String[] NAMES = {"Müller","Schmidt","Weber","Fischer","Wagner","Becker","Hoffmann","Keller","Brandt","Krause","Lang","Vogel","Roth","Kaiser","Berger","Huber"};
    static final Map<UUID, Long> squadCooldown = new HashMap<>();

    public ArmyWarMod() {
        ArmyData.load();
        // Forge (EventBus 7): Listener registrieren. Bei älterem Forge stattdessen
        // MinecraftForge.EVENT_BUS.addListener(...) verwenden.
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(ArmyWarMod::onLogin);
        LivingDeathEvent.BUS.addListener(ArmyWarMod::onDeath);
        LivingChangeTargetEvent.BUS.addListener(ArmyWarMod::onTarget);
        EntityJoinLevelEvent.BUS.addListener(ArmyWarMod::onJoin);
        RegisterCommandsEvent.BUS.addListener(ArmyWarMod::onCommands);
        EntityMountEvent.BUS.addListener(ArmyWarMod::onMount);
    }

    // ---------- Hilfsfunktionen ----------
    static ChatFormatting color(String army) { return army.equals("rot") ? ChatFormatting.RED : ChatFormatting.BLUE; }
    static String armyName(String army) { return army.equals("rot") ? "Rote Armee" : "Blaue Armee"; }
    static String enemyOf(String army) { return army.equals("rot") ? "blau" : "rot"; }

    static String armyOf(Entity e) {
        Team t = e.getTeam();
        if (t != null && t.getName().startsWith("armywar_")) return t.getName().substring(8);
        return null;
    }

    static void addToTeam(MinecraftServer server, Entity e, String army) {
        Scoreboard sb = server.getScoreboard();
        String name = "armywar_" + army;
        PlayerTeam t = sb.getPlayerTeam(name);
        if (t == null) t = sb.addPlayerTeam(name);
        t.setColor(color(army));
        t.setAllowFriendlyFire(false);
        t.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS); // Namen nur fürs eigene Team sichtbar
        t.setDisplayName(Component.literal(armyName(army)));
        sb.addPlayerToTeam(e.getScoreboardName(), t);
    }

    static void msg(ServerPlayer p, String text, ChatFormatting f) {
        p.sendSystemMessage(Component.literal(text).withStyle(f));
    }

    static Rank rankOf(ServerPlayer p) {
        ArmyData.Rec r = ArmyData.get(p.getUUID());
        if (r == null) return Rank.SOLDAT;
        String leader = ArmyData.leaders.get(r.army);
        boolean free = leader == null || leader.equals(p.getUUID().toString());
        return Rank.forXp(r.xp, free);
    }

    static void joinArmy(ServerPlayer p, String army) {
        String old = ArmyData.get(p.getUUID()) == null ? null : ArmyData.get(p.getUUID()).army;
        if (old != null && p.getUUID().toString().equals(ArmyData.leaders.get(old))) ArmyData.leaders.remove(old);
        ArmyData.Rec r = new ArmyData.Rec();
        r.army = army; r.xp = 0;
        ArmyData.players.put(p.getUUID().toString(), r);
        addToTeam(p.level().getServer(), p, army);
        ArmyData.save();
        msg(p, "Du dienst jetzt als einfacher Soldat in der " + armyName(army) + "!", color(army));
    }

    static void addXp(ServerPlayer p, int amount) {
        ArmyData.Rec rec = ArmyData.get(p.getUUID());
        if (rec == null) return;
        Rank before = rankOf(p);
        rec.xp += amount;
        Rank after = rankOf(p);
        if (after == Rank.ANFUEHRER) ArmyData.leaders.put(rec.army, p.getUUID().toString());
        ArmyData.save();
        if (after.ordinal() > before.ordinal()) {
            msg(p, "★ BEFÖRDERUNG! Du bist jetzt " + after.title + "!", ChatFormatting.GOLD);
            reward(p, after);
            if (after == Rank.ANFUEHRER) {
                p.level().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal(p.getName().getString() + " führt jetzt die " + armyName(rec.army) + "!")
                        .withStyle(ChatFormatting.GOLD), false);
            }
        } else {
            msg(p, "+" + amount + " XP (" + rec.xp + (after.next() != null ? "/" + after.next().xpNeeded : "") + ")", ChatFormatting.GRAY);
        }
    }

    static void reward(ServerPlayer p, Rank r) {
        List<ItemStack> items = new ArrayList<>();
        switch (r) {
            case GEFREITER -> items.add(new ItemStack(Items.IRON_SWORD));
            case UNTEROFFIZIER -> { items.add(new ItemStack(Items.IRON_HELMET)); items.add(new ItemStack(Items.IRON_CHESTPLATE)); }
            case LEUTNANT -> { items.add(new ItemStack(Items.IRON_LEGGINGS)); items.add(new ItemStack(Items.IRON_BOOTS)); items.add(new ItemStack(Items.GOLDEN_APPLE, 3)); }
            case HAUPTMANN -> { items.add(new ItemStack(Items.DIAMOND_SWORD)); items.add(new ItemStack(Items.GOLDEN_APPLE, 5)); }
            case MAJOR -> { items.add(new ItemStack(Items.DIAMOND_CHESTPLATE)); items.add(new ItemStack(Items.DIAMOND_HELMET)); }
            case GENERAL -> { items.add(new ItemStack(Items.DIAMOND_LEGGINGS)); items.add(new ItemStack(Items.DIAMOND_BOOTS)); items.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE)); }
            case ANFUEHRER -> { items.add(new ItemStack(Items.NETHERITE_SWORD)); items.add(new ItemStack(Items.TOTEM_OF_UNDYING)); }
            default -> {}
        }
        for (ItemStack s : items) if (!p.getInventory().add(s)) p.drop(s, false);
        if (r.isOfficer()) msg(p, "Du kannst jetzt mit /army squad Truppen rufen (" + r.squadSize() + " Soldaten).", ChatFormatting.YELLOW);
    }

    // ---------- NPC-Soldaten ----------
    static void spawnSoldier(ServerLevel lvl, String army, double x, double z) {
        Zombie z0 = (Zombie) EntityType.ZOMBIE.create(lvl, EntitySpawnReason.COMMAND);
        if (z0 == null) return;
        int y = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        z0.setPos(x, y, z);
        z0.setBaby(false);
        z0.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        z0.setItemSlot(EquipmentSlot.HEAD, new ItemStack(army.equals("rot") ? Items.GOLDEN_HELMET : Items.IRON_HELMET));
        z0.setCustomName(Component.literal("Soldat " + NAMES[new Random().nextInt(NAMES.length)]).withStyle(color(army)));
        z0.setCustomNameVisible(true);
        z0.setPersistenceRequired();
        z0.setGlowingTag(true); // Teamfarbe als Umriss
        addToTeam(lvl.getServer(), z0, army);
        lvl.addFreshEntity(z0); // Ziel-KI wird in onJoin angehängt
    }

    /** Sucht regelmäßig den nächsten Feind und setzt ihn als Ziel. */
    static class EnemyScanGoal extends Goal {
        final Mob mob; int cd = 0;
        EnemyScanGoal(Mob m) { mob = m; setFlags(EnumSet.of(Flag.TARGET)); }
        @Override public boolean canUse() {
            if (++cd < 10) return false;
            cd = 0;
            String mine = armyOf(mob);
            if (mine == null) return false;
            LivingEntity cur = mob.getTarget();
            if (cur != null && cur.isAlive()) return false;
            String enemy = enemyOf(mine);
            List<LivingEntity> list = mob.level().getEntitiesOfClass(LivingEntity.class,
                mob.getBoundingBox().inflate(28),
                e -> e.isAlive() && enemy.equals(armyOf(e))
                     && !(e instanceof Player p && (p.isCreative() || p.isSpectator())));
            LivingEntity best = null; double bd = Double.MAX_VALUE;
            for (LivingEntity e : list) { double d = mob.distanceToSqr(e); if (d < bd) { bd = d; best = e; } }
            if (best != null) mob.setTarget(best);
            return false;
        }
    }

    // ---------- Events ----------
    static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        if (e.getEntity() instanceof Mob m && armyOf(m) != null) m.targetSelector.addGoal(1, new EnemyScanGoal(m));
    }

    /** Soldaten greifen nur Feinde an, niemals Verbündete oder Zivilisten. */
    static boolean onTarget(LivingChangeTargetEvent e) {
        String mine = armyOf(e.getEntity());
        if (mine == null || !(e.getEntity() instanceof Mob)) return false;
        LivingEntity t = e.getNewAboutToBeSetTarget();
        if (t == null) return false;
        return !enemyOf(mine).equals(armyOf(t)); // true = Änderung abbrechen
    }

    static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        ArmyData.Rec r = ArmyData.get(p.getUUID());
        if (r == null) {
            String army = ArmyData.count("rot") <= ArmyData.count("blau") ? "rot" : "blau";
            joinArmy(p, army);
            msg(p, "Befehl: Töte Feinde für XP. /army status zeigt deinen Rang.", ChatFormatting.YELLOW);
        } else addToTeam(p.level().getServer(), p, r.army);
    }

    static boolean onDeath(LivingDeathEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer killer)) return false;
        ArmyData.Rec kr = ArmyData.get(killer.getUUID());
        String victimArmy = armyOf(e.getEntity());
        if (kr == null || victimArmy == null || victimArmy.equals(kr.army)) return false;
        addXp(killer, e.getEntity() instanceof Player ? 50 : 10);
        return false; // Tod nicht abbrechen
    }

    /** Feindliche Spieler dürfen unsere Fahrzeuge nicht besteigen. */
    static boolean onMount(EntityMountEvent e) {
        if (!e.isMounting() || e.getEntityMounting().level().isClientSide()) return false;
        String va = armyOf(e.getEntityBeingMounted());
        return va != null && e.getEntityMounting() instanceof Player && !va.equals(armyOf(e.getEntityMounting()));
    }

    // ---------- Befehle ----------
    static void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        Military.register(d);
        d.register(Commands.literal("army")
            .then(Commands.literal("status").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                ArmyData.Rec r = ArmyData.get(p.getUUID());
                if (r == null) { msg(p, "Du bist in keiner Armee.", ChatFormatting.RED); return 0; }
                Rank rk = rankOf(p);
                String next = rk.next() == null ? "höchster Rang" : "nächster Rang: " + rk.next().title + " ab " + rk.next().xpNeeded + " XP";
                msg(p, armyName(r.army) + " | " + rk.title + " | " + r.xp + " XP | " + next, color(r.army));
                return 1;
            }))
            .then(Commands.literal("join").then(Commands.literal("rot").executes(c -> {
                joinArmy(c.getSource().getPlayerOrException(), "rot"); return 1;
            })).then(Commands.literal("blau").executes(c -> {
                joinArmy(c.getSource().getPlayerOrException(), "blau"); return 1;
            })))
            .then(Commands.literal("squad").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                ArmyData.Rec r = ArmyData.get(p.getUUID());
                Rank rk = rankOf(p);
                if (r == null || !rk.isOfficer()) { msg(p, "Nur Offiziere (ab Leutnant) dürfen Truppen rufen.", ChatFormatting.RED); return 0; }
                ServerLevel lvl = (ServerLevel) p.level();
                long now = lvl.getGameTime();
                Long last = squadCooldown.get(p.getUUID());
                if (last != null && now - last < 1200) { msg(p, "Truppen sind noch unterwegs (Cooldown 60s).", ChatFormatting.RED); return 0; }
                squadCooldown.put(p.getUUID(), now);
                Random rnd = new Random();
                for (int i = 0; i < rk.squadSize(); i++)
                    spawnSoldier(lvl, r.army, p.getX() + rnd.nextInt(7) - 3, p.getZ() + rnd.nextInt(7) - 3);
                msg(p, rk.squadSize() + " Soldaten melden sich zum Dienst!", color(r.army));
                return 1;
            }))
            .then(Commands.literal("battle").executes(c -> {
                // Startet eine Schlacht: 8 Soldaten pro Seite, Feinde ~30 Blöcke vor dir
                ServerPlayer p = c.getSource().getPlayerOrException();
                ArmyData.Rec r = ArmyData.get(p.getUUID());
                if (r == null) return 0;
                ServerLevel lvl = (ServerLevel) p.level();
                Vec3 f = p.getLookAngle(); Vec3 fwd = new Vec3(f.x, 0, f.z).normalize();
                Vec3 side = new Vec3(-fwd.z, 0, fwd.x);
                for (int i = 0; i < 8; i++) {
                    double o = (i - 3.5) * 2.5;
                    spawnSoldier(lvl, r.army, p.getX() - fwd.x * 6 + side.x * o, p.getZ() - fwd.z * 6 + side.z * o);
                    spawnSoldier(lvl, enemyOf(r.army), p.getX() + fwd.x * 30 + side.x * o, p.getZ() + fwd.z * 30 + side.z * o);
                }
                msg(p, "Die Schlacht beginnt! Für die " + armyName(r.army) + "!", ChatFormatting.GOLD);
                return 1;
            })));
    }
}
