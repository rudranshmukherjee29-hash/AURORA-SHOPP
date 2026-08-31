package com.aurorashop.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * All inventory reads/writes used by transactions go through here so the
 * counting logic, the removal logic, and the "is this really a plain
 * vanilla item" check only exist in one place and can't drift apart.
 * <p>
 * Typed against {@link PlayerInventory} specifically (not the generic
 * {@code Inventory}) because {@code getStorageContents()}/
 * {@code setStorageContents()} — which deliberately exclude armor and
 * off-hand slots — are only declared there.
 * <p>
 * We deliberately do NOT use {@code Inventory#removeItem} for sells: that
 * method's leftover-handling semantics are easy to misuse and don't give
 * us an easy way to verify we removed the *exact* amount we counted. We
 * walk the contents array ourselves instead.
 */
public final class InventoryUtil {

    private InventoryUtil() {
    }

    /**
     * Returns true if {@code stack} is a plain, unmodified instance of its
     * material — no custom display name, no lore, no enchantments, no
     * custom model data, no other extra ItemMeta flags. This is the check
     * used to prevent selling a renamed/enchanted copy of an item as if it
     * were the plain version.
     */
    public static boolean isPlainVanilla(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                || meta.hasCustomModelData() || meta.hasAttributeModifiers()
                || meta.isUnbreakable() || !meta.getItemFlags().isEmpty()) {
            return false;
        }
        // A freshly-created ItemStack(material) round-trips to an equivalent
        // meta with nothing set; comparing against that catches any other
        // NBT (e.g. PersistentDataContainer entries) an exploit might rely on.
        ItemStack reference = new ItemStack(stack.getType());
        return stack.isSimilar(reference);
    }

    /**
     * Counts how many units of {@code material} in {@code inventory} qualify
     * for selling: correct material, correct stack-size semantics, and (if
     * {@code requireVanillaOnly}) a plain, unmodified item per
     * {@link #isPlainVanilla}. Never mutates the inventory.
     */
    public static long countSellable(PlayerInventory inventory, Material material, boolean requireVanillaOnly) {
        long total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType() != material) {
                continue;
            }
            if (requireVanillaOnly && !isPlainVanilla(stack)) {
                continue;
            }
            total += stack.getAmount();
        }
        return total;
    }

    /**
     * Removes exactly {@code amount} matching units from {@code inventory}.
     * Returns the number actually removed, which the caller MUST verify
     * equals {@code amount} before proceeding — if it doesn't (which should
     * never happen if {@link #countSellable} was called first on the same
     * unmodified inventory), the caller is responsible for restoring
     * whatever was removed.
     */
    public static long removeExact(PlayerInventory inventory, Material material, long amount, boolean requireVanillaOnly) {
        long remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            if (requireVanillaOnly && !isPlainVanilla(stack)) {
                continue;
            }
            int stackAmount = stack.getAmount();
            if (stackAmount <= remaining) {
                remaining -= stackAmount;
                contents[i] = null;
            } else {
                stack.setAmount((int) (stackAmount - remaining));
                remaining = 0;
            }
        }
        inventory.setStorageContents(contents);
        return amount - remaining;
    }

    /**
     * Best-effort estimate of how many additional units of {@code material}
     * (respecting its max stack size) the inventory has room for, counting
     * both existing partial stacks and fully empty slots. Used to size
     * "max affordable" purchases and to reject buys that clearly won't fit
     * before we ever touch the player's balance.
     */
    public static long freeCapacityFor(PlayerInventory inventory, Material material) {
        int maxStackSize = material.getMaxStackSize();
        long capacity = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null) {
                capacity += maxStackSize;
            } else if (stack.getType() == material && stack.getAmount() < maxStackSize) {
                capacity += (maxStackSize - stack.getAmount());
            }
        }
        return capacity;
    }
}
