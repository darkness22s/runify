package me.zombie_striker.qg.preview;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.client.item.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HitPreviewClient implements ClientModInitializer {
    private static final Pattern DAMAGE_LINE = Pattern.compile("(?i).*damage\\s*[:=]\\s*(-?\\d+(?:[.,]\\d+)?).*");
    private static final int RED_LOW = 0xFF6B5E;
    private static final int RED_MEDIUM = 0xFFFF3B30;
    private static final int RED_HIGH = 0xFFB00020;
    private static final int RED_LETHAL = 0xFFFF1744;

    @Override
    public void onInitializeClient() {
        // Rendering is injected into the vanilla crosshair so it stays aligned with the GUI scale.
    }

    public static boolean renderCrosshair(DrawContext context, MinecraftClient client, EntityHitResult hit) {
        Entity entity = hit.getEntity();
        if (!(entity instanceof LivingEntity)) {
            return false;
        }

        LivingEntity target = (LivingEntity) entity;
        double rawDamage = readWeaponDamage(client);
        if (rawDamage <= 0) {
            return false;
        }

        double aimDamage = isHeadshot(target, hit) ? rawDamage * 2.0 : rawDamage;
        double damage = applyArmor(target, aimDamage);
        double healthPool = target.getHealth() + target.getAbsorptionAmount();
        boolean lethal = damage >= healthPool;
        double ratio = healthPool <= 0 ? 1 : damage / healthPool;
        int color = lethal ? RED_LETHAL : ratio >= 0.65 ? RED_HIGH : ratio >= 0.3 ? RED_MEDIUM : RED_LOW;

        int centerX = context.getScaledWindowWidth() / 2;
        int centerY = context.getScaledWindowHeight() / 2;
        // Draw a compact plus-shaped reticle. The shade represents estimated damage, while
        // the brightest shade indicates an estimated lethal hit.
        context.fill(centerX - 4, centerY, centerX + 5, centerY + 1, color);
        context.fill(centerX, centerY - 4, centerX + 1, centerY + 5, color);

        String preview = lethal ? "LETHAL · " : "";
        preview += String.format(java.util.Locale.ROOT, "%.1f damage", damage);
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(preview), centerX, centerY + 10, color);
        return true;
    }

    private static double readWeaponDamage(MinecraftClient client) {
        ItemStack held = client.player.getMainHandStack();
        if (held.isEmpty()) {
            return -1;
        }
        for (Text line : held.getTooltip(Item.TooltipContext.DEFAULT, client.player, TooltipType.BASIC)) {
            Matcher matcher = DAMAGE_LINE.matcher(line.getString());
            if (matcher.matches()) {
                try {
                    return Double.parseDouble(matcher.group(1).replace(',', '.'));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static double applyArmor(LivingEntity target, double damage) {
        double armor = target.getAttributeValue(EntityAttributes.GENERIC_ARMOR);
        double toughness = target.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
        double effectiveArmor = Math.max(armor / 5.0, armor - damage / (2.0 + toughness / 4.0));
        effectiveArmor = Math.min(20.0, effectiveArmor);
        return damage * (1.0 - effectiveArmor / 25.0);
    }

    private static boolean isHeadshot(LivingEntity target, EntityHitResult hit) {
        double headThreshold = target.getBoundingBox().minY + target.getHeight() * 0.82;
        return hit.getPos().y >= headThreshold;
    }
}
