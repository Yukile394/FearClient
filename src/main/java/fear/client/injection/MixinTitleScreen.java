package fear.client.injection;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fear.client.core.manager.client.ModuleManager;
import fear.client.features.modules.client.ClientSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.util.Util;
import fear.client.FearClient;
import net.minecraft.client.util.InputUtil;
import fear.client.gui.misc.DialogScreen;
import fear.client.utility.render.TextureStorage;

import java.net.URI;

import static fear.client.features.modules.Module.mc;
import static fear.client.features.modules.client.ClientSettings.isRu;

@Mixin(TitleScreen.class)
public class MixinTitleScreen extends Screen {
    protected MixinTitleScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    public void postInitHook(CallbackInfo ci) {
        // Özel ana menü devre dışı bırakıldı — Minecraft'ın varsayılan menüsü gösterilir.
        // P tuşu bind edilmemişse otomatik olarak ata
        if (ModuleManager.clickGui.getBind().getKey() == -1) {
            ModuleManager.clickGui.setBind(InputUtil.fromTranslationKey("key.keyboard.p").getCode(), false, false);
        }
    }
}
