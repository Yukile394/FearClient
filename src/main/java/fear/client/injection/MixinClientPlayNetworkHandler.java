package fear.client.injection;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fear.client.FearClient;
import fear.client.core.Managers;
import fear.client.core.manager.client.ModuleManager;
import fear.client.features.modules.Module;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinClientPlayNetworkHandler {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void sendChatMessageHook(@NotNull String message, CallbackInfo ci) {
        if (message.equals(String.valueOf(ModuleManager.unHook.code)) && ModuleManager.unHook.isEnabled())
            ModuleManager.unHook.disable();

        if(Module.fullNullCheck()) return;
        if (message.startsWith(Managers.COMMAND.getPrefix())) {
            try {
                Managers.COMMAND.getDispatcher().execute(
                        message.substring(Managers.COMMAND.getPrefix().length()),
                        Managers.COMMAND.getSource()
                );
            } catch (CommandSyntaxException ignored) {}

            ci.cancel();
        }
    }
}
