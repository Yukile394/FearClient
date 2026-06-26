package fear.client.features.modules.player;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.jetbrains.annotations.NotNull;
import fear.client.events.impl.PacketEvent;
import fear.client.injection.accesors.IPlayerMoveC2SPacket;
import fear.client.features.modules.Module;
import fear.client.setting.Setting;

public class AntiHunger extends Module {
    public AntiHunger() {
        super("AntiHunger", Category.PLAYER);
    }

    private final Setting<Boolean> ground = new Setting<>("CancelGround", true);
    private final Setting<Boolean> sprint = new Setting<>("CancelSprint", true);


    @EventHandler
    public void onPacketSend(PacketEvent.@NotNull Send e) {
        if (e.getPacket() instanceof PlayerMoveC2SPacket pac && ground.getValue())
            ((IPlayerMoveC2SPacket) pac).setOnGround(false);

        if (e.getPacket() instanceof ClientCommandC2SPacket pac && sprint.getValue())
            if (pac.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING)
                e.cancel();
    }
}
