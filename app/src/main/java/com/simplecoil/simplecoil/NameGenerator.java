package com.simplecoil.simplecoil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class NameGenerator {
    private static final String[] COOL_NAMES = new String[] {
            "Iron Man", "Mr. Freeze", "Spider-Man", "Batman", "Captain America",
            "Thor", "Wolverine", "Deadpool", "Black Panther", "Doctor Strange",
            "Hulk", "Star-Lord", "Groot", "Hawkeye", "Venom", "Magneto",
            "Joker", "Riddler", "Penguin", "Daredevil", "Punisher", "Cyclops",
            "Storm", "Nightcrawler", "Flash", "Green Lantern", "Aquaman",
            "Cyborg", "Superboy", "Robin", "Mystique", "Rogue", "Beast",
            "Ant-Man", "Wasp", "Gamora", "Drax", "Nebula", "Loki", "Thanos",
            "Superman", "Wonder Woman", "Supergirl", "Batgirl", "Nightwing",
            "Shazam", "Green Arrow", "Beast Boy", "Raven", "Starfire",
            "Bucky Barnes", "Falcon", "Vision", "Scarlet Witch", "Quicksilver",
            "Moon Knight", "Ghost Rider", "Blade", "Silver Surfer", "Nova"
    };

    private static final Random random = new Random();

    public static String getRandomName() {
        return getRandomUniqueName(null);
    }

    public static String getRandomUniqueName(Set<String> usedNames) {
        List<String> available = new ArrayList<>();
        for (String name : COOL_NAMES) {
            if (usedNames == null || !usedNames.contains(name)) {
                available.add(name);
            }
        }
        if (available.isEmpty()) {
            int index = random.nextInt(COOL_NAMES.length);
            return COOL_NAMES[index] + " " + (random.nextInt(89) + 10);
        }
        return available.get(random.nextInt(available.size()));
    }
}
