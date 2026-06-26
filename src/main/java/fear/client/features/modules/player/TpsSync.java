package fear.client.features.modules.player;

import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import fear.client.FearClient;
import fear.client.core.Managers;
import fear.client.core.manager.client.ModuleManager;
import fear.client.events.impl.EventTick;
import fear.client.features.modules.Module;

public class TpsSync extends Module {
    public TpsSync() {
        super("TpsSync", Module.Category.PLAYER);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTick(EventTick e) {
        if (ModuleManager.timer.isEnabled()) return;
        if (Managers.SERVER.getTPS() > 1)
            FearClient.TICK_TIMER = Managers.SERVER.getTPS() / 20f;
        else FearClient.TICK_TIMER = 1f;
    }

    @Override
    public void onDisable() {
        FearClient.TICK_TIMER = 1f;
    }
}
