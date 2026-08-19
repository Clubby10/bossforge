package com.lucasdunn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public final class DefaultConfigurationTest {

    private static Map<String, Object> configuration;

    @BeforeClass
    @SuppressWarnings("unchecked")
    public static void loadConfiguration() {
        InputStream stream = DefaultConfigurationTest.class
                .getClassLoader()
                .getResourceAsStream("config.yml");
        assertNotNull("config.yml must be packaged", stream);
        configuration = (Map<String, Object>) new Yaml().load(stream);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void guiUsesLegacySafeSizeAndTitle() {
        Map<String, Object> settings = (Map<String, Object>) configuration.get("settings");
        int size = (Integer) settings.get("gui-size");
        String title = translateColors((String) settings.get("gui-title"));

        assertTrue(size >= 9 && size <= 54 && size % 9 == 0);
        assertTrue("1.8 inventory titles are limited to 32 characters", title.length() <= 32);
        assertEquals(
                "Direct craft commands must be disabled by default",
                Boolean.FALSE,
                settings.get("craft-command-enabled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void recipesUseUniqueValidSlotsAndKnownMaterials() {
        Map<String, Object> settings = (Map<String, Object>) configuration.get("settings");
        int size = (Integer) settings.get("gui-size");
        Map<String, Object> materials =
                (Map<String, Object>) configuration.get("materials");
        Map<String, Object> recipes =
                (Map<String, Object>) configuration.get("recipes");
        Set<Integer> slots = new HashSet<Integer>();

        for (Map.Entry<String, Object> entry : recipes.entrySet()) {
            Map<String, Object> recipe = (Map<String, Object>) entry.getValue();
            int slot = (Integer) recipe.get("display-slot");
            assertTrue(slot >= 0 && slot < size);
            assertTrue("Duplicate recipe slot " + slot, slots.add(slot));

            Map<String, Object> ingredients =
                    (Map<String, Object>) recipe.get("ingredients");
            for (Map.Entry<String, Object> ingredient : ingredients.entrySet()) {
                assertTrue(materials.containsKey(ingredient.getKey()));
                assertTrue(((Integer) ingredient.getValue()) > 0);
            }
        }

        assertEquals(5, recipes.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void npcSetupUsesCitizensConsoleCommand() {
        Map<String, Object> npc = (Map<String, Object>) configuration.get("npc");
        assertEquals("Citizens", npc.get("citizens-plugin-name"));
        assertEquals("&5&l»BossMage«", npc.get("default-name"));
        assertEquals("Wizard", npc.get("skin-name"));
        assertEquals("bosscrafting open <p>", npc.get("open-command"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void permissionNodesAreConfigurable() {
        Map<String, Object> permissions =
                (Map<String, Object>) configuration.get("permissions");
        assertEquals("bosscrafting.craft", permissions.get("craft"));
        assertEquals("bosscrafting.admin", permissions.get("admin"));
    }

    private static String translateColors(String value) {
        return value.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }
}
