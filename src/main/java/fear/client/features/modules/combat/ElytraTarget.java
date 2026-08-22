package fear.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import fear.client.events.impl.EventAttack;
import fear.client.events.impl.EventPostSync;
import fear.client.features.modules.Module;
import fear.client.setting.Setting;
import fear.client.utility.Timer;
import fear.client.utility.player.InventoryUtility;
import fear.client.utility.player.SearchInvResult;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ElytraTarget extends Module {

    // ══════════════════════════════════════════════════════
    //  FİŞEK AYARLARI
    // ══════════════════════════════════════════════════════
    private final Setting<Boolean> rocketBoost =
            new Setting<>("FisekBoost", true);

    /** Fişek atma hızı (milisaniye). 30 ms gibi düşük değerler çok hızlı atar. */
    private final Setting<Integer> rocketDelayMs =
            new Setting<>("FisekGecikmesiMs", 30, 0, 1000,
                    v -> rocketBoost.getValue());

    private final Setting<Boolean> alwaysBoost =
            new Setting<>("HepBoost", false,
                    v -> rocketBoost.getValue());

    // ══════════════════════════════════════════════════════
    //  YAKINDA KILIÇ
    // ══════════════════════════════════════════════════════
    private final Setting<Boolean> meleeSwitch =
            new Setting<>("YakinDovus", true);

    /** Hedef bu menzile girdiğinde fişeği bırakıp kılıca geçer */
    private final Setting<Float> meleeRange =
            new Setting<>("KilicMesafesi", 5.0f, 1.5f, 15.0f,
                    v -> meleeSwitch.getValue());

    private final Setting<Boolean> meleeAttack =
            new Setting<>("YakinVurus", true,
                    v -> meleeSwitch.getValue());

    private final Setting<Boolean> meleeCrit =
            new Setting<>("YakinKritik", true,
                    v -> meleeSwitch.getValue() && meleeAttack.getValue());

    private final Setting<Boolean> autoSharpestSword =
            new Setting<>("OtoKilic", true);

    // ══════════════════════════════════════════════════════
    //  HASAR GÖSTERGESİ
    // ══════════════════════════════════════════════════════
    private final Setting<Boolean> damageDisplay =
            new Setting<>("HasarGostergesi", true);

    private final Setting<Boolean> showCritInfo =
            new Setting<>("KritikGoster", true,
                    v -> damageDisplay.getValue());

    private final Setting<Boolean> showTargetHealth =
            new Setting<>("HedefCan", true,
                    v -> damageDisplay.getValue());

    private final Setting<Integer> damageDisplayTime =
            new Setting<>("HasarSuresi", 2000, 500, 5000,
                    v -> damageDisplay.getValue());

    // ══════════════════════════════════════════════════════
    //  HEDEF AYARLARI
    // ══════════════════════════════════════════════════════
    private final Setting<Float> targetRange =
            new Setting<>("HedefMenzil", 64f, 5f, 128f);

    private final Setting<Boolean> onlyWhenFlying =
            new Setting<>("SadeceUcarken", true);

    private final Setting<Boolean> followTarget =
            new Setting<>("HedefiTakipEt", true);

    private final Setting<Float> followSpeed =
            new Setting<>("TakipHizi", 0.8f, 0.1f, 3.0f,
                    v -> followTarget.getValue());

    private final Setting<Float> orbitRadius =
            new Setting<>("YorungeMesafesi", 4.0f, 1.0f, 15.0f,
                    v -> followTarget.getValue());

    private final Setting<Boolean> interceptTarget =
            new Setting<>("HedefOnuneGec", true,
                    v -> followTarget.getValue());

    // ══════════════════════════════════════════════════════
    //  KRİTİK VURUŞ VE BYPASS
    // ══════════════════════════════════════════════════════
    private final Setting<CritMode> critMode =
            new Setting<>("KritikModu", CritMode.Strict);

    private final Setting<Integer> jitterMs =
            new Setting<>("JitterMs", 15, 0, 150);

    // ══════════════════════════════════════════════════════
    //  DAHILI DURUM
    // ══════════════════════════════════════════════════════
    private final Timer rocketTimer = new Timer();
    private final Timer meleeTimer  = new Timer();
    private final Timer damageTimer = new Timer();

    private double orbitAngle       = 0.0;
    private float  lastTargetHealth = -1f;

    private final Deque<DamageEntry> damageLog = new ArrayDeque<>(3);

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    // ══════════════════════════════════════════════════════
    //  ANA TİK (SLOT VE MESAFE YÖNETİMİ)
    // ══════════════════════════════════════════════════════
    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        // Derlenme hatası vermemesi için kendi hedef bulucumuzu kullanıyoruz
        LivingEntity target = findClosestTarget();
        if (target == null) return;

        if (onlyWhenFlying.getValue() && !mc.player.isFallFlying()) return;

        double dist = mc.player.distanceTo(target);
        if (dist > targetRange.getValue() && !alwaysBoost.getValue()) return;

        boolean inMeleeRange = dist <= meleeRange.getValue();

        SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
        SearchInvResult rocket = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);

        // Hedefe doğru yönel/takip et
        if (followTarget.getValue() && dist <= targetRange.getValue()) {
            orbit(target, dist);
        }

        if (meleeSwitch.getValue() && inMeleeRange) {
            // ── YAKIN DÖVÜŞ (Hedefe Yetiştik): Fişeği bırak, kılıcı eline al ──
            if (autoSharpestSword.getValue() && sword.found()) {
                if (mc.player.getInventory().selectedSlot != sword.slot()) {
                    mc.player.getInventory().selectedSlot = sword.slot();
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
                    }
                }
            }
            handleMelee(target);
        } else {
            // ── UZAK (Hedef Kaçıyor): Kılıca geçme, fişek elinde kalsın ve bas ──
            if (rocketBoost.getValue() && rocket.found()) {
                if (mc.player.getInventory().selectedSlot != rocket.slot()) {
                    mc.player.getInventory().selectedSlot = rocket.slot();
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(rocket.slot()));
                    }
                }

                if (rocketTimer.passedMs(rocketDelayMs.getValue() + jitter())) {
                    // SequencedPacket kullanmak yerine doğal sağ tık atıyoruz (derlenme sorunlarını çözer)
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    rocketTimer.reset();
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  HEDEF BULUCU (Aura.target Hatalarını Önler)
    // ══════════════════════════════════════════════════════
    private LivingEntity findClosestTarget() {
        LivingEntity closest = null;
        double minDistance = targetRange.getValue();

        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof LivingEntity le && le != mc.player && le.isAlive()) {
                double dist = mc.player.distanceTo(le);
                if (dist <= minDistance) {
                    closest = le;
                    minDistance = dist;
                }
            }
        }
        return closest;
    }

    // ══════════════════════════════════════════════════════
    //  YAKINDA KILIÇLA VUR
    // ══════════════════════════════════════════════════════
    private void handleMelee(Entity target) {
        if (!meleeAttack.getValue()) return;

        // Saldırı cooldown kontrolü (Spam olmasın diye)
        if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f) return;

        // Gecikme kontrolü
        if (!meleeTimer.passedMs(100 + jitter())) return;

        // Vurmadan önce kritik paketlerini gönder
        if (meleeCrit.getValue()) {
            sendCritPacket();
        }

        // Hasar tespiti için canı kaydet
        if (target instanceof LivingEntity le) {
            lastTargetHealth = le.getHealth();
        }

        // Vuruşu gerçekleştir
        mc.interactionManager.attackEntity(mc.player, target);
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
        mc.player.swingHand(Hand.MAIN_HAND);
        meleeTimer.reset();
    }

    // ══════════════════════════════════════════════════════
    //  HASAR OLAYINI YAKALA
    // ══════════════════════════════════════════════════════
    @EventHandler
    public void onAttack(EventAttack e) {
        if (!damageDisplay.getValue()) return;
        if (e.isPre()) {
            if (e.getEntity() instanceof LivingEntity le) {
                lastTargetHealth = le.getHealth();
            }
            return;
        }
        if (!(e.getEntity() instanceof LivingEntity le)) return;

        float dmg = lastTargetHealth - le.getHealth();
        if (dmg <= 0f) return;

        boolean crit = mc.player.fallDistance > 0
                && !mc.player.isOnGround()
                && !mc.player.isClimbing()
                && !mc.player.isSubmergedInWater()
                || mc.player.isFallFlying();

        if (damageLog.size() >= 3) damageLog.pollFirst();
        damageLog.addLast(new DamageEntry(dmg, crit, le.getHealth()));
        damageTimer.reset();
    }

    // ══════════════════════════════════════════════════════
    //  2D RENDER — HASAR GÖSTERGESİ
    // ══════════════════════════════════════════════════════
    @Override
    public void onRender2D(DrawContext ctx) {
        if (!damageDisplay.getValue()) return;
        if (damageLog.isEmpty()) return;

        if (damageTimer.passedMs(damageDisplayTime.getValue())) {
            damageLog.clear();
            return;
        }

        int cx = mc.getWindow().getScaledWidth()  / 2;
        int cy = mc.getWindow().getScaledHeight() / 2;
        int y  = cy + 6;

        int idx = 0;
        for (DamageEntry entry : damageLog) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("§c-%.1f", entry.damage));
            if (showCritInfo.getValue()     && entry.crit) sb.append(" §e✦KRİT");
            if (showTargetHealth.getValue())               sb.append(String.format(" §7❤§c%.1f", entry.hp));

            ctx.drawTextWithShadow(
                    mc.textRenderer,
                    Text.literal(sb.toString()),
                    cx + 8,
                    y + idx * 10,
                    0xFF_FF4444
            );
            idx++;
        }
    }

    // ══════════════════════════════════════════════════════
    //  YÖRÜNGELİ TAKİP (YETİŞMEK İÇİN)
    // ══════════════════════════════════════════════════════
    private void orbit(Entity target, double dist) {
        Vec3d targetPos    = target.getPos();
        Vec3d targetVel    = target.getVelocity();
        float speed        = followSpeed.getValue();
        float radius       = orbitRadius.getValue();

        Vec3d predictedPos = targetPos;
        if (interceptTarget.getValue() && target instanceof LivingEntity le && le.isFallFlying()) {
            double ticks = dist / Math.max(speed * 2.0, 0.1);
            predictedPos = targetPos.add(targetVel.multiply(ticks));
        }

        orbitAngle += 0.04 * speed;

        double tx  = predictedPos.x + Math.cos(orbitAngle) * radius;
        double ty  = predictedPos.y + 2.0;
        double tz  = predictedPos.z + Math.sin(orbitAngle) * radius;
        double dx  = tx - mc.player.getX();
        double dy  = ty - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz  = tz - mc.player.getZ();
        double hd  = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hd));
        float cy    = mc.player.getYaw();
        float cp    = mc.player.getPitch();

        mc.player.setYaw  (cy + MathHelper.wrapDegrees(yaw   - cy) * speed * 0.3f);
        mc.player.setPitch(cp + MathHelper.wrapDegrees(pitch - cp) * speed * 0.3f);
    }

    // ══════════════════════════════════════════════════════
    //  GÜVENLİ KRİTİK PAKET GÖNDERİMİ (KICK YEMEMEK İÇİN)
    // ══════════════════════════════════════════════════════
    private void sendCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        if (mc.getNetworkHandler() == null) return;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        switch (critMode.getValue()) {
            case Packet -> {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0000002718, z, false));
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false));
            }
            case Strict -> {
                // Sunucudan atmaması (kick/ban) için güvenli SemiAntiBot/NCP bypass değerleri
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0625, z, false));
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.07260029960661, z, false));
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.02260029910661, z, false));
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false));
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  YARDIMCILAR
    // ══════════════════════════════════════════════════════
    private long jitter() {
        return (long) (Math.random() * jitterMs.getValue());
    }

    // ══════════════════════════════════════════════════════
    //  İÇ SINIFLAR
    // ══════════════════════════════════════════════════════
    private record DamageEntry(float damage, boolean crit, float hp) {}

    public enum CritMode { Packet, Strict }
            }
            
