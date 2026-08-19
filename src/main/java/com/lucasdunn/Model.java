package com.lucasdunn;

import java.util.LinkedHashMap;
import org.bukkit.inventory.ItemStack;

final class MaterialDef {
    final String id;
    final String name;
    final ItemStack item;

    MaterialDef(String id, String name, ItemStack item) {
        this.id = id;
        this.name = name;
        this.item = item;
    }
}

final class RecipeDef {
    final String id;
    final String name;
    final int slot;
    final ItemStack icon;
    final LinkedHashMap<String, Integer> ingredients;

    RecipeDef(
            String id,
            String name,
            int slot,
            ItemStack icon,
            LinkedHashMap<String, Integer> ingredients) {
        this.id = id;
        this.name = name;
        this.slot = slot;
        this.icon = icon;
        this.ingredients = ingredients;
    }
}

final class RegisteredBatch {
    final String materialId;
    int remainingAmount;

    RegisteredBatch(String materialId, int remainingAmount) {
        this.materialId = materialId;
        this.remainingAmount = remainingAmount;
    }

    RegisteredBatch copy() {
        return new RegisteredBatch(materialId, remainingAmount);
    }

    int spendableAmount(int physicalAmount) {
        return Math.max(0, Math.min(physicalAmount, remainingAmount));
    }

    void consume(int amount) {
        if (amount < 0 || amount > remainingAmount) {
            throw new IllegalArgumentException("Invalid registered batch consumption");
        }
        remainingAmount -= amount;
    }
}
