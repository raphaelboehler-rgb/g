package com.example.armywar;

public enum Rank {
    SOLDAT("Soldat", 0),
    GEFREITER("Gefreiter", 40),
    UNTEROFFIZIER("Unteroffizier", 120),
    LEUTNANT("Leutnant", 260),
    HAUPTMANN("Hauptmann", 450),
    MAJOR("Major", 700),
    GENERAL("General", 1100),
    ANFUEHRER("Anführer", 1800);

    public final String title;
    public final int xpNeeded;

    Rank(String title, int xpNeeded) { this.title = title; this.xpNeeded = xpNeeded; }

    /** Offiziere (Leutnant+) dürfen Truppen rufen. */
    public boolean isOfficer() { return ordinal() >= LEUTNANT.ordinal(); }

    /** Anzahl Soldaten pro /army squad. */
    public int squadSize() { return isOfficer() ? 2 * (ordinal() - 2) : 0; }

    public Rank next() { return this == ANFUEHRER ? null : values()[ordinal() + 1]; }

    public static Rank forXp(int xp, boolean leaderSlotFree) {
        Rank r = SOLDAT;
        for (Rank c : values()) if (xp >= c.xpNeeded) r = c;
        if (r == ANFUEHRER && !leaderSlotFree) r = GENERAL; // nur ein Anführer pro Armee
        return r;
    }
}
