package com.example.armywar;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Spezialisierungen (Panzerfahrer / Pilot), Waffenkammer und Fahrzeuge. */
public class Military {
    static final Map<UUID, Long> vehicleCd = new HashMap<>();
    static final Map<UUID, Long> arsenalCd = new HashMap<>();
    static final Map<UUID, UUID> vehicles = new HashMap<>();

    static ItemStack named(Item item, int n, String name) {
        ItemStack s = new ItemStack(item, n);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.GOLD));
        return s;
    }

    static void give(ServerPlayer p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("army")
            .then(Commands.literal("role")
                .then(Commands.literal("infanterie").executes(c -> setRole(c.getSource().getPlayerOrException(), "infanterie")))
                .then(Commands.literal("panzer").executes(c -> setRole(c.getSource().getPlayerOrException(), "panzer")))
                .then(Commands.literal("pilot").executes(c -> setRole(c.getSource().getPlayerOrException(), "pilot"))))
            .then(Commands.literal("arsenal").executes(c -> arsenal(c.getSource().getPlayerOrException())))
            .then(Commands.literal("vehicle")
                .then(Commands.literal("panzer").executes(c -> vehicle(c.getSource().getPlayerOrException(), "panzer")))
                .then(Commands.literal("heli").executes(c -> vehicle(c.getSource().getPlayerOrException(), "heli")))
                .then(Commands.literal("jet").executes(c -> vehicle(c.getSource().getPlayerOrException(), "jet")))));
    }

    static int setRole(ServerPlayer p, String role) {
        ArmyData.Rec r = ArmyData.get(p.getUUID());
        if (r == null) return 0;
        Rank rk = ArmyWarMod.rankOf(p);
        if (role.equals("panzer") && rk.ordinal() < Rank.UNTEROFFIZIER.ordinal()) {
            ArmyWarMod.msg(p, "Panzerfahrer ab Rang Unteroffizier.", ChatFormatting.RED); return 0;
        }
        if (role.equals("pilot") && rk.ordinal() < Rank.LEUTNANT.ordinal()) {
            ArmyWarMod.msg(p, "Pilot ab Rang Leutnant (Kampfjet ab Major).", ChatFormatting.RED); return 0;
        }
        r.role = role;
        ArmyData.save();
        ArmyWarMod.msg(p, "Neue Verwendung: " + role + ". Fahrzeuge mit /army vehicle ...", ChatFormatting.GREEN);
        return 1;
    }

    static int arsenal(ServerPlayer p) {
        ArmyData.Rec r = ArmyData.get(p.getUUID());
        if (r == null) return 0;
        long now = p.level().getGameTime();
        Long last = arsenalCd.get(p.getUUID());
        if (last != null && now - last < 2400) { ArmyWarMod.msg(p, "Waffenkammer: bitte 2 Minuten warten.", ChatFormatting.RED); return 0; }
        arsenalCd.put(p.getUUID(), now);
        int o = ArmyWarMod.rankOf(p).ordinal();
        give(p, new ItemStack(Items.ARROW, 32));
        if (o >= Rank.GEFREITER.ordinal())     give(p, named(Items.CROSSBOW, 1, "Sturmgewehr"));
        if (o >= Rank.UNTEROFFIZIER.ordinal()) give(p, named(Items.WIND_CHARGE, 4, "Handgranate"));
        if (o >= Rank.HAUPTMANN.ordinal())     give(p, named(Items.BOW, 1, "Scharfschützengewehr"));
        if (o >= Rank.MAJOR.ordinal())         give(p, named(Items.TNT, 8, "Sprengladung"));
        ArmyWarMod.msg(p, "Du hast Waffen aus der Waffenkammer erhalten.", ChatFormatting.GREEN);
        return 1;
    }

    static int vehicle(ServerPlayer p, String type) {
        ArmyData.Rec r = ArmyData.get(p.getUUID());
        if (r == null) return 0;
        Rank rk = ArmyWarMod.rankOf(p);
        boolean ok = switch (type) {
            case "panzer" -> r.role.equals("panzer");
            case "heli" -> r.role.equals("pilot");
            default -> r.role.equals("pilot") && rk.ordinal() >= Rank.MAJOR.ordinal();
        };
        if (!ok) { ArmyWarMod.msg(p, "Dafür fehlt dir die Verwendung/der Rang (/army role ...).", ChatFormatting.RED); return 0; }

        ServerLevel lvl = (ServerLevel) p.level();
        long now = lvl.getGameTime();
        Long last = vehicleCd.get(p.getUUID());
        if (last != null && now - last < 3600) { ArmyWarMod.msg(p, "Fahrzeug-Nachschub: 3 Minuten Cooldown.", ChatFormatting.RED); return 0; }
        vehicleCd.put(p.getUUID(), now);

        UUID oldId = vehicles.get(p.getUUID());
        if (oldId != null) { Entity old = lvl.getEntity(oldId); if (old != null) old.discard(); }

        Vec3 f = p.getLookAngle();
        Vec3 pos = p.position().add(f.x * 4, 0, f.z * 4);

        Mob v;
        String name;
        if (type.equals("panzer")) {
            // Platzhalter-Panzer: riesiges, gepanzertes, gesatteltes Pferd (vanilla, ohne eigene Modelle)
            v = (Mob) EntityType.HORSE.create(lvl, EntitySpawnReason.COMMAND);
            if (v == null) return 0;
            ((net.minecraft.world.entity.animal.horse.Horse) v).setTamed(true);
            v.setItemSlot(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
            v.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.DIAMOND_HORSE_ARMOR));
            setAttr(v, Attributes.SCALE, 2.0);
            setAttr(v, Attributes.MAX_HEALTH, 120);
            setAttr(v, Attributes.MOVEMENT_SPEED, 0.28);
            setAttr(v, Attributes.JUMP_STRENGTH, 0.2);
            setAttr(v, Attributes.ARMOR, 20);
            name = "Panzer";
        } else {
            // Platzhalter-Flieger: Happy Ghast mit Geschirr (fliegt, lenkbar mit Blickrichtung/WASD)
            v = (Mob) EntityType.HAPPY_GHAST.create(lvl, EntitySpawnReason.COMMAND);
            if (v == null) return 0;
            v.setItemSlot(EquipmentSlot.BODY, new ItemStack(r.army.equals("rot") ? Items.RED_HARNESS : Items.BLUE_HARNESS));
            if (type.equals("heli")) {
                setAttr(v, Attributes.MAX_HEALTH, 80);
                setAttr(v, Attributes.FLYING_SPEED, 0.10);
                name = "Helikopter";
            } else {
                setAttr(v, Attributes.SCALE, 0.6);
                setAttr(v, Attributes.MAX_HEALTH, 50);
                setAttr(v, Attributes.FLYING_SPEED, 0.30);
                name = "Kampfjet";
            }
        }
        v.setPos(pos.x, pos.y, pos.z);
        v.setHealth(v.getMaxHealth());
        v.setCustomName(Component.literal(name + " (" + p.getName().getString() + ")").withStyle(ArmyWarMod.color(r.army)));
        v.setCustomNameVisible(true);
        v.setPersistenceRequired();
        ArmyWarMod.addToTeam(lvl.getServer(), v, r.army);
        lvl.addFreshEntity(v);
        vehicles.put(p.getUUID(), v.getUUID());
        p.startRiding(v);
        ArmyWarMod.msg(p, name + " bereit! (Nur deine Armee kann einsteigen.)", ChatFormatting.GOLD);
        return 1;
    }

    static void setAttr(LivingEntity e, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double v) {
        var inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }
}
