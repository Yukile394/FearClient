package fear.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import fear.client.core.manager.client.ModuleManager;
import fear.client.events.impl.EventPostSync;
import fear.client.features.modules.Module;
import fear.client.setting.Setting;
import fear.client.utility.Timer;
import fear.client.utility.player.InventoryUtility;
import fear.client.utility.player.PlayerUtility;
import fear.client.utility.player.SearchInvResult;

public final class ElytraTarget extends Module {

    /*   ROCKET SETTINGS   */
    private final Setting<Boolean> rocketBoost = new Setting<>("RocketBoost", true);
    private final Setting<Boolean> useWhileHoldingAnyItem = new Setting<>("UseRocketsWhileHoldingAnyItem", true, v -> rocketBoost.getValue());
    // Çok daha agresif delay - neredeyse sürekli fişek
    private final Setting<Integer> minDelay = new Setting<>("MinimumDelay", 0, 0, 500, v -> rocketBoost.getValue());
    private final Setting<Integer> maxDelay = new Setting<>("MaximumDelay", 20, 0, 500, v -> rocketBoost.getValue());
    private final Setting<Boolean> silentRockets = new Setting<>("SilentRocketUsage", true, v -> rocketBoost.getValue());
    private final Setting<Boolean> autoSwitchRocket = new Setting<>("AutoSwitchRocket", true, v -> rocketBoost.getValue());
    // Hedef yokken de fişek bas (her zaman aktif)
    private final Setting<Boolean> alwaysBoost = new Setting<>("AlwaysBoost", false, v -> rocketBoost.getValue());
    private final Setting<Priority> priority = new Setting<>("PrioritySettings", Priority.RocketFirst, v -> rocketBoost.getValue());

    /*   TARGET SETTINGS   */
    private final Setting<Float> targetRange = new Setting<>("TargetRange", 40f, 5f, 128f);
    private final Setting<Boolean> onlyWhileChasing = new Setting<>("OnlyWhileChasingTarget", false);
    private final Setting<Boolean> onlyWhenFlying = new Setting<>("OnlyWhenFlying", true);

    /*   SWORD SETTINGS   */
    private final Setting<Boolean> autoSharpestSword = new Setting<>("AutoSwitchToSharpestSword", true);

    private final Timer rocketTimer = new Timer();

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    @EventHandler
    public void onPostSync(EventPostSync e) {
        Entity target = Aura.target;

        boolean hasValidTarget = target != null && PlayerUtility.squaredDistanceFromEyes(target.getPos()) < (targetRange.getValue() * targetRange.getValue());

        // alwaysBoost açıksa hedef koşulunu atla
        boolean shouldBoost = alwaysBoost.getValue() || hasValidTarget;

        if (onlyWhileChasing.getValue() && !hasValidTarget) return;
        if (onlyWhenFlying.getValue() && !mc.player.isFallFlying()) return;

        // Auto-switch to the highest-Sharpness sword
        if (autoSharpestSword.getValue() && hasValidTarget) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && mc.player.getInventory().selectedSlot != sword.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
            }
        }

        if (!rocketBoost.getValue()) return;
        if (!shouldBoost) return;

        boolean holdingSomethingElse = mc.player.getMainHandStack().getItem() != Items.FIREWORK_ROCKET;
        if (holdingSomethingElse && !useWhileHoldingAnyItem.getValue()) return;

        // Agresif delay - min ile max arasında çok kısa süre bekle
        int delay = minDelay.getValue() >= maxDelay.getValue()
                ? minDelay.getValue()
                : (int) (minDelay.getValue() + Math.random() * (maxDelay.getValue() - minDelay.getValue()));

        if (!rocketTimer.passedMs(delay)) return;

        // Birden fazla fişek bas (hız için)
        fireRocket();
        if (delay == 0) {
            // delay 0 ise aynı tick içinde bir tane daha bas
            fireRocket();
        }
        rocketTimer.reset();
    }

    private void fireRocket() {
        SearchInvResult rocketHotbar = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int rocketSlot = rocketHotbar.slot();

        if (rocketSlot == -1) {
            if (!autoSwitchRocket.getValue()) return;
            // Envanterin herhangi bir yerinde ara
            SearchInvResult rocketAnywhere = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!rocketAnywhere.found()) return;
            return;
        }

        int prevSlot = mc.player.getInventory().selectedSlot;
        boolean needsSwap = prevSlot != rocketSlot;

        if (silentRockets.getValue()) {
            if (needsSwap) sendPacket(new UpdateSelectedSlotC2SPacket(rocketSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (needsSwap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (needsSwap) InventoryUtility.switchTo(rocketSlot);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    public enum Priority {
        RocketFirst, SwordFirst
    }
}

