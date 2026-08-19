package com.lucasdunn;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.bukkit.inventory.ItemStack;

final class HiddenItemData {

    private static final String MATERIAL_KEY = "BossCraftingMaterial";
    private static final String BATCH_KEY = "BossCraftingBatch";

    private static final Method AS_NMS_COPY;
    private static final Method AS_BUKKIT_COPY;
    private static final Method GET_TAG;
    private static final Method SET_TAG;
    private static final Method SET_STRING;
    private static final Method GET_STRING;
    private static final Method HAS_KEY;
    private static final Method REMOVE;
    private static final Constructor<?> TAG_CONSTRUCTOR;

    static {
        try {
            Class<?> craftItemStack = Class.forName(
                    "org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack");
            Class<?> nmsItemStack = Class.forName("net.minecraft.server.v1_8_R3.ItemStack");
            Class<?> nbtTagCompound = Class.forName(
                    "net.minecraft.server.v1_8_R3.NBTTagCompound");

            AS_NMS_COPY = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            AS_BUKKIT_COPY = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
            GET_TAG = nmsItemStack.getMethod("getTag");
            SET_TAG = nmsItemStack.getMethod("setTag", nbtTagCompound);
            SET_STRING = nbtTagCompound.getMethod("setString", String.class, String.class);
            GET_STRING = nbtTagCompound.getMethod("getString", String.class);
            HAS_KEY = nbtTagCompound.getMethod("hasKey", String.class);
            REMOVE = nbtTagCompound.getMethod("remove", String.class);
            TAG_CONSTRUCTOR = nbtTagCompound.getConstructor();
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private HiddenItemData() {
    }

    static ItemStack apply(ItemStack item, String materialId, String batchId) {
        try {
            Object nmsItem = AS_NMS_COPY.invoke(null, item);
            Object tag = GET_TAG.invoke(nmsItem);
            if (tag == null) {
                tag = TAG_CONSTRUCTOR.newInstance();
            }

            SET_STRING.invoke(tag, MATERIAL_KEY, materialId);
            SET_STRING.invoke(tag, BATCH_KEY, batchId);
            SET_TAG.invoke(nmsItem, tag);
            return (ItemStack) AS_BUKKIT_COPY.invoke(null, nmsItem);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not write hidden item data", exception);
        }
    }

    static String getMaterialId(ItemStack item) {
        return getString(item, MATERIAL_KEY);
    }

    static String getBatchId(ItemStack item) {
        return getString(item, BATCH_KEY);
    }

    static ItemStack withoutBossCraftingData(ItemStack item) {
        try {
            Object nmsItem = AS_NMS_COPY.invoke(null, item);
            Object tag = GET_TAG.invoke(nmsItem);
            if (tag != null) {
                REMOVE.invoke(tag, MATERIAL_KEY);
                REMOVE.invoke(tag, BATCH_KEY);
                SET_TAG.invoke(nmsItem, tag);
            }
            return (ItemStack) AS_BUKKIT_COPY.invoke(null, nmsItem);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read hidden item data", exception);
        }
    }

    private static String getString(ItemStack item, String key) {
        if (item == null) {
            return null;
        }

        try {
            Object nmsItem = AS_NMS_COPY.invoke(null, item);
            Object tag = GET_TAG.invoke(nmsItem);
            if (tag == null || !((Boolean) HAS_KEY.invoke(tag, key))) {
                return null;
            }
            return (String) GET_STRING.invoke(tag, key);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read hidden item data", exception);
        }
    }
}
