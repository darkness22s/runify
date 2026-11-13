package me.zombie_striker.qg.preview.mixin;

import me.zombie_striker.qg.preview.HitPreviewClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void qualityarmory$renderPreviewCrosshair(DrawContext context, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !(client.crosshairTarget instanceof EntityHitResult)) {
            return;
        }
        if (HitPreviewClient.renderCrosshair(context, client, (EntityHitResult) client.crosshairTarget)) {
            ci.cancel();
        }
    }
}
