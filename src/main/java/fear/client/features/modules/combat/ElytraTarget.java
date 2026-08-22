package fear.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import fear.client.events.impl.EventPostSync;
import fear.client.features.modules.Module;
import fear.client.setting.Setting;
import fear.client.utility.Timer;
import fear.client.utility.player.InventoryUtility;
import fear.client.utility.player.SearchInvResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

public final class ElytraTarget extends Module {

    public enum CritMode  { Packet, Strict }
    public enum BypassMode { Off, GrimAC }

    /* ── ROCKET & KOVALAMA (YENİ & ÖZEL) ──────────────────────────────── */
    private final Setting<Integer> rocketDelayMs = new Setting<>("RocketDelay(ms)", 30, 0, 1000);
    private final Setting<Float>   catchUpSpeed  = new Setting<>("CatchUpSpeed", 2.2f, 0.5f, 5.0f);
    private final Setting<Boolean> catchUpBoost  = new Setting<>("CatchUpBoost", true);

    /* ── HEDEF & MESAFE ─────────────────────────────────────────────────── */
    private final Setting<Float>   targetRange   = new Setting<>("TargetRange", 64f, 5f, 128f);
    private final Setting<Float>   attackRange   = new Setting<>("AttackRange", 3.8f, 1.0f, 6.0f);
    private final Setting<Boolean> onlyWhenFlying= new Setting<>("OnlyWhenFlying", true);

    /* ── KRİTİK VURUŞ ───────────────────────────────────────────────────── */
    private final Setting<Boolean>  autoCrit = new Setting<>("AutoCrit", true);
    private final Setting<CritMode> critMode = new Setting<>("CritMode", CritMode.Packet, v -> autoCrit.getValue());

    /* ── BYPASS & GRIMAC KORUMALARI ─────────────────────────────────────── */
    private final Setting<BypassMode> bypassMode   = new Setting<>("BypassMode", BypassMode.GrimAC);
    private final Setting<Boolean>  fakeLag      = new Setting<>("FakeLag", true, v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Integer>  lagTicks     = new Setting<>("LagTicks", 6, 2, 20, v -> bypassMode.getValue() != BypassMode.Off && fakeLag.getValue());
    private final Setting<Boolean>  rotationNoise= new Setting<>("RotationNoise", true, v -> bypassMode.getValue() != BypassMode.Off);
    private final Setting<Float>    noiseStrength= new Setting<>("NoiseStrength", 0.08f, 0.01f, 0.5f, v -> bypassMode.getValue() != BypassMode.Off && rotationNoise.getValue());

    /* ── ZAMANLAYICILAR & DURUM ─────────────────────────────────────────── */
    private final Timer rocketTimer = new Timer();
    private final Timer attackTimer = new Timer();
    private final Timer lagTimer    = new Timer();

    private final Deque<Packet<?>> lagQueue = new ArrayDeque<>();
    private Entity target = null;
    private float lastYaw = 0, lastPitch = 0;

    public ElytraTarget() {
        // Module only accepts (name, category) – description is auto-generated
        super("ElytraTarget", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        target = null;
        rocketTimer.reset();
        attackTimer.reset();
        lagTimer.reset();
        lagQueue.clear();
    }

    @Override
    public void onDisable() {
        flushLagQueue();
    }

    @EventHandler
    public void onPostSync(EventPostSync event) {
        if (mc.player == null || mc.world == null) return;
        if (onlyWhenFlying.getValue() && !mc.player.isFallFlying()) return;

        findTarget();
        if (target == null) {
            flushLagQueue();
            return;
        }

        // Rotasyon gürültüsü ve GrimAC yönlendirmesi
        handleBypassRotations();

        double distance = mc.player.distanceTo(target);

        // UZAKTA: Sadece fişek elimizde kalsın, kılıca geçmesin, hızlanarak yaklaşalım
        if (distance > attackRange.getValue()) {
            chaseTarget();
        }
        // YAKINDA: Hedefin dibine girdik, slot kılıca geçsin ve kritik yapıştıralım
        else {
            attackTarget();
        }
    }

    /**
     * Uzaktayken fişeği elinde tutar, ayarlanan milisaniyede bir (örn. 30ms) basar.
     * Kaçan oyuncuya yetişmek için ek hızlandırma (CatchUp) uygular.
     */
    private void chaseTarget() {
        // Correct method name: findItemInHotBar
        SearchInvResult rocketResult = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);

        if (rocketResult.found()) {
            // Uzaktayken ELİMİZDE FİŞEK OLSUN (Kılıca geçmez)
            switchToSlot(rocketResult.slot());

            if (rocketTimer.passedMs(rocketDelayMs.getValue())) {
                // New constructor: Hand, sequence, yaw, pitch
                // Prefer the sequenced helper from Module when available
                sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                        Hand.MAIN_HAND,
                        id,
                        mc.player.getYaw(),
                        mc.player.getPitch()
                ));
                mc.player.swingHand(Hand.MAIN_HAND);
                rocketTimer.reset();
            }
        }

        // Kaçan oyuncuya yetişme (Catch-Up Boost)
        if (catchUpBoost.getValue() && mc.player.isFallFlying()) {
            Vec3d targetPos = target.getPos().add(0, target.getHeight() / 2.0, 0);
            Vec3d dir = targetPos.subtract(mc.player.getPos()).normalize();
            mc.player.setVelocity(dir.x * catchUpSpeed.getValue(), dir.y * catchUpSpeed.getValue(), dir.z * catchUpSpeed.getValue());
        }
    }

    /**
     * Hedefe yakınlaşınca kılıca geçer ve GrimAC bypass korumalı kritik vurur.
     */
    private void attackTarget() {
        int swordSlot = findSwordSlot();
        if (swordSlot != -1) {
            // Yakına girince anında kılıca geçiş
            switchToSlot(swordSlot);
        }

        if (mc.player.getAttackCooldownProgress(0.5f) >= 1.0f) {
            if (autoCrit.getValue()) {
                doBypassCrit();
            }

            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
            attackTimer.reset();
        }
    }

    /**
     * GrimAC Anti-Cheat sistemini yanıltmak için rotasyonlara gürültü ekler ve paketleri hafif geciktirir.
     */
    private void handleBypassRotations() {
        if (bypassMode.getValue() == BypassMode.Off || target == null) return;

        Vec3d targetPos = target.getPos().add(0, target.getHeight() / 2.0, 0);
        double diffX = targetPos.x - mc.player.getX();
        // getEyeHeight() now requires EntityPose – use getStandingEyeHeight() or current pose
        double diffY = targetPos.y - (mc.player.getY() + mc.player.getStandingEyeHeight());
        double diffZ = targetPos.z - mc.player.getZ();

        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(diffY, dist)));

        if (rotationNoise.getValue()) {
            yaw += (ThreadLocalRandom.current().nextFloat() - 0.5f) * noiseStrength.getValue() * 10f;
            pitch += (ThreadLocalRandom.current().nextFloat() - 0.5f) * noiseStrength.getValue() * 10f;
        }

        lastYaw = yaw;
        lastPitch = pitch;

        // GrimAC sunucu tarafında view-lock yememek için rotasyon paketi gönderilir
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround()));
    }

    /**
     * Sunucudan ban yemeden/atılmadan güvenli kritik vuruş paketleri.
     */
    private void doBypassCrit() {
        if (bypassMode.getValue() == BypassMode.Off) return;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        if (critMode.getValue() == CritMode.Packet) {
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0625, z, false));
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false));
        } else {
            // Strict Grim Crit Bypass
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.11, z, false));
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.1100013579, z, false));
            mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0000013579, z, false));
        }
    }

    private void switchToSlot(int slot) {
        if (mc.player.getInventory().selectedSlot != slot) {
            mc.player.getInventory().selectedSlot = slot;
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private int findSwordSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof SwordItem) {
                return i;
            }
        }
        return -1;
    }

    private void findTarget() {
        target = null;
        double closest = targetRange.getValue();

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity && entity != mc.player && entity.isAlive()) {
                double dist = mc.player.distanceTo(entity);
                if (dist < closest) {
                    closest = dist;
                    target = entity;
                }
            }
        }
    }

    private void flushLagQueue() {
        while (!lagQueue.isEmpty()) {
            mc.player.networkHandler.sendPacket(lagQueue.poll());
        }
    }
}
